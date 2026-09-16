package ca.mpreg.webgpuviewer.draw

import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUVertexState
import androidx.webgpu.PrimitiveTopology
import ca.mpreg.webgpuviewer.renderer.FormatKeyed
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.renderer.setTransientBindGroup
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val device get() = WebGpuRenderer.device

private val pipelines = FormatKeyed { format ->
    val shaderModule = device.createShaderModule(
        GPUShaderModuleDescriptor(
            shaderSourceWGSL = GPUShaderSourceWGSL(ARC_SHADER)
        )
    )
    device.createRenderPipeline(
        GPURenderPipelineDescriptor(
            vertex = GPUVertexState(module = shaderModule, entryPoint = "vs_main"),
            fragment = GPUFragmentState(
                module = shaderModule, entryPoint = "fs_main", targets = arrayOf(
                    GPUColorTargetState(
                        format = format, blend = GPUBlendState(
                            color = GPUBlendComponent(
                                srcFactor = BlendFactor.SrcAlpha,
                                dstFactor = BlendFactor.OneMinusSrcAlpha,
                                operation = BlendOperation.Add
                            ), alpha = GPUBlendComponent(
                                srcFactor = BlendFactor.One,
                                dstFactor = BlendFactor.OneMinusSrcAlpha,
                                operation = BlendOperation.Add
                            )
                        )
                    )
                )
            ),
            primitive = GPUPrimitiveState(topology = PrimitiveTopology.TriangleList)
        )
    )
}

// Everything in target pixels, as Circle.kt's, so the arc stays round on any aspect ratio. Unlike
// Circle.kt the quad only covers the arc's own square rather than the whole target: a progress
// ring is redrawn every frame, and a full-target quad would shade every pixel of the screen to
// draw a few hundred of them.
private const val ARC_SHADER = """
struct Params {
    center: vec2<f32>,
    target_size: vec2<f32>,
    radius: f32,
    half_width: f32,
    start: f32,  // radians, clockwise from 3 o'clock
    sweep: f32,  // radians, clockwise
    color: vec4<f32>,
}

@group(0) @binding(0) var<uniform> params: Params;

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
}

const TAU: f32 = 6.283185307179586;

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOutput {
    var corners = array<vec2<f32>, 6>(
        vec2<f32>(-1.0, -1.0),
        vec2<f32>(-1.0, 1.0),
        vec2<f32>(1.0, -1.0),
        vec2<f32>(1.0, -1.0),
        vec2<f32>(-1.0, 1.0),
        vec2<f32>(1.0, 1.0)
    );
    let extent = params.radius + params.half_width + 1.0;
    let px = params.center + corners[vertex_index] * extent;

    var out: VertexOutput;
    out.position = vec4<f32>(
        px.x / params.target_size.x * 2.0 - 1.0,
        1.0 - px.y / params.target_size.y * 2.0,
        0.0,
        1.0
    );
    return out;
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let p = in.position.xy - params.center;

    var dist: f32;
    if (params.sweep >= TAU) {
        dist = abs(length(p) - params.radius);
    } else {
        // How far round from the start this pixel sits, in [0, TAU).
        let along = (atan2(p.y, p.x) - params.start) - TAU * floor((atan2(p.y, p.x) - params.start) / TAU);
        if (along <= params.sweep) {
            dist = abs(length(p) - params.radius);
        } else {
            // Past either end: the round cap there.
            let head = params.radius * vec2<f32>(cos(params.start), sin(params.start));
            let end = params.start + params.sweep;
            let tail = params.radius * vec2<f32>(cos(end), sin(end));
            dist = min(length(p - head), length(p - tail));
        }
    }

    let coverage = clamp(params.half_width - dist + 0.5, 0.0, 1.0);
    return vec4<f32>(params.color.rgb, params.color.a * coverage);
}
"""

private val byteBufferLocal = ThreadLocal.withInitial {
    ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder())
}

/**
 * Strokes an arc with round caps into an existing render pass - the shape of a Material circular
 * progress indicator. [cx]/[cy]/[radius]/[strokeWidth] are in the target's pixels, [radius] to the
 * middle of the stroke. Angles are degrees clockwise from 3 o'clock, as Compose's `drawArc` takes
 * them; a [sweepDegrees] of 360 or more is a whole ring. Sets its own pipeline, so the caller must
 * set theirs again before drawing something else.
 */
fun Draw.arc(
    pass: GPURenderPassEncoder,
    /** Format of [pass]'s colour attachment - see [FormatKeyed]. */
    format: Int,
    targetWidth: Int,
    targetHeight: Int,
    cx: Float,
    cy: Float,
    radius: Float,
    strokeWidth: Float,
    startDegrees: Float,
    sweepDegrees: Float,
    color: Int,
) {
    if (sweepDegrees <= 0f || color ushr 24 == 0) return

    val byteBuffer = byteBufferLocal.get()
    byteBuffer.clear()
    byteBuffer.putFloat(cx)
    byteBuffer.putFloat(cy)
    byteBuffer.putFloat(targetWidth.toFloat())
    byteBuffer.putFloat(targetHeight.toFloat())
    byteBuffer.putFloat(radius)
    byteBuffer.putFloat(strokeWidth / 2f)
    byteBuffer.putFloat(Math.toRadians(startDegrees.toDouble()).toFloat())
    byteBuffer.putFloat(Math.toRadians(sweepDegrees.toDouble()).toFloat())
    byteBuffer.putColor(color)
    byteBuffer.flip()

    // Per call, as Draw.rect's: several arcs can share one pass.
    val uniformBuffer = device.createBuffer(
        GPUBufferDescriptor(size = 48L, usage = BufferUsage.Uniform or BufferUsage.CopyDst)
    )
    device.queue.writeBuffer(uniformBuffer, 0, byteBuffer)

    val pipeline = pipelines[format]
    pass.setPipeline(pipeline)
    pass.setTransientBindGroup(
        0, device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = pipeline.getBindGroupLayout(0), entries = arrayOf(
                    GPUBindGroupEntry(0, buffer = uniformBuffer)
                )
            )
        )
    )
    pass.draw(6)
    uniformBuffer.close()
}

/** Writes an ARGB colour int as the four straight-alpha floats the shape shaders take. */
internal fun ByteBuffer.putColor(color: Int) {
    putFloat(((color shr 16) and 0xFF) / 255f)
    putFloat(((color shr 8) and 0xFF) / 255f)
    putFloat((color and 0xFF) / 255f)
    putFloat(((color ushr 24) and 0xFF) / 255f)
}
