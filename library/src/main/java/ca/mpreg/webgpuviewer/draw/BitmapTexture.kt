package ca.mpreg.webgpuviewer.draw

import android.graphics.Bitmap
import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.BufferUsage
import androidx.webgpu.FilterMode
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUSamplerDescriptor
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUTextureView
import androidx.webgpu.GPUVertexState
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import ca.mpreg.webgpuviewer.renderer.FormatKeyed
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.renderer.destroyAndRelease
import ca.mpreg.webgpuviewer.renderer.setTransientBindGroup
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val device get() = WebGpuRenderer.device

/**
 * An Android [Bitmap] for a [ca.mpreg.webgpuviewer.viewer.ImagePage.Render] page to draw with
 * [Draw.bitmap] - something the platform already knows how to draw, such as a laid-out view or a
 * composable with its cards, icons and buttons, rather than a rebuild of it from rects and text.
 *
 * The pixels are copied out when this is built, so the bitmap can be recycled straight after. They
 * reach the GPU on the first draw, on the render thread, and are dropped then. The texture is this
 * object's to [release] once the page is done with it.
 */
class BitmapTexture(bitmap: Bitmap) {
    val width: Int = bitmap.width
    val height: Int = bitmap.height

    private var pixels: ByteBuffer? = pixelsOf(bitmap)
    private var texture: GPUTexture? = null
    private var view: GPUTextureView? = null
    private var released = false

    /** The view to sample, uploading the pixels first if this is the first draw. Null once released. */
    @Synchronized
    internal fun viewForDraw(): GPUTextureView? {
        if (released) return null
        view?.let { return it }
        val data = pixels ?: return null
        val created = device.createTexture(
            GPUTextureDescriptor(
                size = GPUExtent3D(width, height),
                format = TextureFormat.RGBA8Unorm,
                usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
            )
        )
        device.queue.writeTexture(
            dataLayout = GPUTexelCopyBufferLayout(
                offset = 0L,
                bytesPerRow = width * 4,
                rowsPerImage = height,
            ),
            data = data,
            destination = GPUTexelCopyTextureInfo(texture = created),
            writeSize = GPUExtent3D(width, height),
        )
        pixels = null
        texture = created
        return created.createView().also { view = it }
    }

    /**
     * Frees the texture. Safe to call more than once and from any thread; a draw after it draws
     * nothing. The free itself waits for the render dispatcher, as an evicted image's does - a frame
     * being recorded there can be holding the view right now. After a lost device the handles are
     * only dropped: freeing them then crashes.
     */
    fun release() {
        val oldView: GPUTextureView?
        val oldTexture: GPUTexture?
        synchronized(this) {
            if (released) return
            released = true
            pixels = null
            oldView = view
            oldTexture = texture
            view = null
            texture = null
        }
        if (oldTexture == null) return
        ImagePage.cleanupScope.launch {
            WebGpuRenderer.onDispatcher {
                if (WebGpuRenderer.isAvailable) {
                    oldView?.close()
                    oldTexture.destroyAndRelease()
                }
            }
        }
    }

    private companion object {
        /**
         * An ARGB_8888 bitmap's pixels are RGBA bytes with the alpha already multiplied in, which is
         * the order the texture wants and the blend [Draw.bitmap] uses. Anything else - a hardware
         * bitmap, say - is copied into that config first.
         */
        fun pixelsOf(bitmap: Bitmap): ByteBuffer {
            val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            // writeTexture's JNI binding needs a direct buffer - see Text.kt.
            val buffer = ByteBuffer.allocateDirect(source.width * source.height * 4)
                .order(ByteOrder.nativeOrder())
            source.copyPixelsToBuffer(buffer)
            if (source !== bitmap) source.recycle()
            buffer.flip()
            return buffer
        }
    }
}

private val pipelines = FormatKeyed { format ->
    val shaderModule = device.createShaderModule(
        GPUShaderModuleDescriptor(
            shaderSourceWGSL = GPUShaderSourceWGSL(BITMAP_SHADER)
        )
    )
    device.createRenderPipeline(
        GPURenderPipelineDescriptor(
            vertex = GPUVertexState(module = shaderModule, entryPoint = "vs_main"),
            fragment = GPUFragmentState(
                module = shaderModule, entryPoint = "fs_main", targets = arrayOf(
                    GPUColorTargetState(
                        // Premultiplied: that is what a bitmap's pixels already are.
                        format = format, blend = GPUBlendState(
                            color = GPUBlendComponent(
                                srcFactor = BlendFactor.One,
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

private val sampler by lazy {
    device.createSampler(
        GPUSamplerDescriptor(magFilter = FilterMode.Linear, minFilter = FilterMode.Linear)
    )
}

private const val BITMAP_SHADER = """
struct Params {
    rect: vec4<f32>,  // left, top, right, bottom - normalised [0, 1] within the destination
    alpha: f32,
    _pad0: f32,
    _pad1: f32,
    _pad2: f32,
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var bitmap_tex: texture_2d<f32>;
@group(0) @binding(2) var bitmap_sampler: sampler;

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
}

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOutput {
    var positions = array<vec2<f32>, 6>(
        vec2<f32>(0.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 1.0)
    );
    let pos = positions[vertex_index];

    let x = mix(params.rect.x, params.rect.z, pos.x);
    let y = mix(params.rect.y, params.rect.w, pos.y);

    var out: VertexOutput;
    out.position = vec4<f32>(x * 2.0 - 1.0, 1.0 - y * 2.0, 0.0, 1.0);
    out.uv = pos;
    return out;
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    return textureSample(bitmap_tex, bitmap_sampler, in.uv) * params.alpha;
}
"""

private val byteBufferLocal = ThreadLocal.withInitial {
    ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder())
}

/**
 * Draws [texture] into [pass], stretched over [x1]/[y1]-[x2]/[y2] in the normalised `[0, 1]`
 * coordinates [Draw.rect] takes, faded by [alpha]. Sets its own pipeline, so the caller must set
 * theirs again before drawing something else. Draws nothing once [texture] has been released.
 */
fun Draw.bitmap(
    pass: GPURenderPassEncoder,
    /** Format of [pass]'s colour attachment - see [FormatKeyed]. */
    format: Int,
    texture: BitmapTexture,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    alpha: Float = 1f,
) {
    val view = texture.viewForDraw() ?: return

    val byteBuffer = byteBufferLocal.get()
    byteBuffer.clear()
    byteBuffer.putFloat(x1)
    byteBuffer.putFloat(y1)
    byteBuffer.putFloat(x2)
    byteBuffer.putFloat(y2)
    byteBuffer.putFloat(alpha)
    byteBuffer.putFloat(0f)
    byteBuffer.putFloat(0f)
    byteBuffer.putFloat(0f)
    byteBuffer.flip()

    // Per call, as Draw.rect's: several bitmaps can share one pass.
    val uniformBuffer = device.createBuffer(
        GPUBufferDescriptor(size = 32L, usage = BufferUsage.Uniform or BufferUsage.CopyDst)
    )
    device.queue.writeBuffer(uniformBuffer, 0, byteBuffer)

    val pipeline = pipelines[format]
    pass.setPipeline(pipeline)
    pass.setTransientBindGroup(
        0, device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = pipeline.getBindGroupLayout(0), entries = arrayOf(
                    GPUBindGroupEntry(0, buffer = uniformBuffer),
                    GPUBindGroupEntry(1, textureView = view),
                    GPUBindGroupEntry(2, sampler = sampler),
                )
            )
        )
    )
    pass.draw(6)
    uniformBuffer.close()
}
