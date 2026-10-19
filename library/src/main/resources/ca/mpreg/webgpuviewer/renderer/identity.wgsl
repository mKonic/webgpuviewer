// Identity: one pass that copies the tile.
//
// Read by ImageFilterWgsl. The format is mpv's user shader headers, with WGSL compute shaders
// (entry point `main`) for the bodies. A `//!` line after a body starts the next pass.
//
// As in mpv:
//   //!DESC text       the pass's name
//   //!HOOK point      MAIN or RGB: the tile, between being drawn and being resized. Required
//   //!BIND name       texture for the pass's next `texture_2d` binding, in binding order.
//                      HOOKED is the tile; any other name is an earlier pass's //!SAVE
//   //!SAVE name       what the pass's `texture_storage_2d` binding writes. Without it the pass
//                      replaces HOOKED, so the last pass has none: that is the network's result.
//                      Storage format comes from the WGSL declaration
//   //!WIDTH expr      size of what the pass writes, by default HOOKED.w and HOOKED.h
//   //!HEIGHT expr
//   //!WHEN expr       the pass runs only for tiles where this is not 0
//   //!COMPONENTS n    checked to be 1 to 4, otherwise unused
//   //!COMPUTE bw bh [tw th]
//                      output pixels per workgroup, and its threads - which must be the WGSL
//                      @workgroup_size. Required: only compute passes are supported
// The expressions are mpv's reverse Polish: numbers, NAME.w and NAME.h (HOOKED, OUTPUT, or an
// earlier //!SAVE), ! + - * / % > < >= <= == != && || and the ternary ?. OUTPUT is the size the
// tile will be resized to, so OUTPUT.w HOOKED.w / is the tile's page scale. In //!WHEN only,
// those are for a nominal 256-pixel tile: ratios mean something, absolute sizes don't.
//
// Not in mpv:
//   //!HALO n          input pixels of surrounding page an output pixel reads, since a tile is not
//                      a whole frame. Before any pass it is the default; in a header, that pass's.
//                      The network needs the most of the passes that run
//
// A network has no resolve of its own: the tile chain resizes its output.
//
// A `sampler` binding gets a linear clamp sampler. Textures are reused once their last reader
// has run.

//!DESC identity
//!HOOK MAIN
//!BIND HOOKED
//!COMPUTE 8 8
@group(0) @binding(0) var tex: texture_2d<f32>;
@group(0) @binding(1) var outputTexture: texture_storage_2d<rgba16float, write>;

@compute @workgroup_size(8, 8)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    if (any(id.xy >= textureDimensions(outputTexture))) {
        return;
    }
    textureStore(outputTexture, id.xy, textureLoad(tex, id.xy, 0));
}
