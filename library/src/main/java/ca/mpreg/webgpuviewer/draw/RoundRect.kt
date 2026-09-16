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
            shaderSourceWGSL = GPUShaderSourceWGSL(ROUND_RECT_SHADER)
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

// Target pixels throughout, as Arc.kt's. The ripple is a disc clipped to the rounded rect, laid
// over the fill, so one draw gives a pressed control's state layer and its ripple together.
private const val ROUND_RECT_SHADER = """
struct Params {
    rect: vec4<f32>,  // left, top, right, bottom in target pixels
    target_size: vec2<f32>,
    corner: f32,
    ripple_radius: f32,
    ripple_center: vec2<f32>,
    _pad: vec2<f32>,
    color: vec4<f32>,
    ripple_color: vec4<f32>,
}

@group(0) @binding(0) var<uniform> params: Params;

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
}

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOutput {
    var corners = array<vec2<f32>, 6>(
        vec2<f32>(0.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 1.0)
    );
    let c = corners[vertex_index];
    let px = vec2<f32>(
        mix(params.rect.x - 1.0, params.rect.z + 1.0, c.x),
        mix(params.rect.y - 1.0, params.rect.w + 1.0, c.y)
    );

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
    let p = in.position.xy;
    let center = (params.rect.xy + params.rect.zw) * 0.5;
    let half_size = (params.rect.zw - params.rect.xy) * 0.5;
    let corner = min(params.corner, min(half_size.x, half_size.y));

    let q = abs(p - center) - (half_size - vec2<f32>(corner));
    let dist = length(max(q, vec2<f32>(0.0))) + min(max(q.x, q.y), 0.0) - corner;
    let shape = clamp(0.5 - dist, 0.0, 1.0);

    let fill = params.color;
    let ripple_cover = clamp(params.ripple_radius - length(p - params.ripple_center) + 0.5, 0.0, 1.0);
    let ripple_a = params.ripple_color.a * ripple_cover;

    // The ripple over the fill, straight alpha.
    let a = ripple_a + fill.a * (1.0 - ripple_a);
    if (a <= 0.0) {
        discard;
    }
    let rgb = (params.ripple_color.rgb * ripple_a + fill.rgb * fill.a * (1.0 - ripple_a)) / a;
    return vec4<f32>(rgb, a * shape);
}
"""

private val byteBufferLocal = ThreadLocal.withInitial {
    ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder())
}

/**
 * Fills a rounded rect into an existing render pass, with an optional ripple disc laid over the
 * fill and clipped to the same shape - what a pressed Material button draws over itself: a state
 * layer, and the ripple spreading from the touch. All in the target's pixels; [cornerRadius] is
 * capped at half the shorter side, so a large one makes a pill. A [rippleRadius] of 0 or a clear
 * [rippleColor] draws the fill alone. Sets its own pipeline, so the caller must set theirs again
 * before drawing something else.
 */
fun Draw.roundRect(
    pass: GPURenderPassEncoder,
    /** Format of [pass]'s colour attachment - see [FormatKeyed]. */
    format: Int,
    targetWidth: Int,
    targetHeight: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerRadius: Float,
    color: Int,
    rippleX: Float = 0f,
    rippleY: Float = 0f,
    rippleRadius: Float = 0f,
    rippleColor: Int = 0,
) {
    if (right <= left || bottom <= top) return
    if (color ushr 24 == 0 && (rippleRadius <= 0f || rippleColor ushr 24 == 0)) return

    val byteBuffer = byteBufferLocal.get()
    byteBuffer.clear()
    byteBuffer.putFloat(left)
    byteBuffer.putFloat(top)
    byteBuffer.putFloat(right)
    byteBuffer.putFloat(bottom)
    byteBuffer.putFloat(targetWidth.toFloat())
    byteBuffer.putFloat(targetHeight.toFloat())
    byteBuffer.putFloat(cornerRadius)
    byteBuffer.putFloat(rippleRadius)
    byteBuffer.putFloat(rippleX)
    byteBuffer.putFloat(rippleY)
    byteBuffer.putFloat(0f)
    byteBuffer.putFloat(0f)
    byteBuffer.putColor(color)
    byteBuffer.putColor(rippleColor)
    byteBuffer.flip()

    // Per call, as Draw.rect's: several shapes can share one pass.
    val uniformBuffer = device.createBuffer(
        GPUBufferDescriptor(size = 80L, usage = BufferUsage.Uniform or BufferUsage.CopyDst)
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
