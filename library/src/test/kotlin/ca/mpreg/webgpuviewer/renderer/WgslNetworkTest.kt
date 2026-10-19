package ca.mpreg.webgpuviewer.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WgslNetworkTest {
    private fun res(n: String) = WgslNetwork::class.java.getResourceAsStream(n)!!.bufferedReader().readText()

    private fun fails(msg: String, src: String) {
        try {
            WgslNetwork.parse(src); fail("accepted: $msg")
        } catch (e: IllegalArgumentException) {
        }
    }

    private val known = { _: String -> true }

    private fun eval(text: String, vararg vars: Pair<String, Double>) = SzExpr.parse(text, known).eval(mapOf(*vars))

    private val pass = """
//!DESC a
//!HOOK MAIN
//!BIND HOOKED
//!COMPUTE 8 8
@group(0) @binding(0) var t: texture_2d<f32>;
@group(0) @binding(1) var o: texture_storage_2d<rgba16float, write>;
@compute @workgroup_size(8, 8)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {}
"""

    @Test
    fun expressions() {
        assertEquals(1.0, eval("OUTPUT.w HOOKED.w / 1.3 >", "OUTPUT.w" to 300.0, "HOOKED.w" to 200.0), 0.0)
        assertEquals(0.0, eval("OUTPUT.w HOOKED.w / 1.3 >", "OUTPUT.w" to 200.0, "HOOKED.w" to 200.0), 0.0)
        assertEquals(2.0, eval("1 2 3 ?"), 0.0)
        assertEquals(3.0, eval("0 2 3 ?"), 0.0)
        assertEquals(42.0, eval("HOOKED.w 2 *", "HOOKED.w" to 21.0), 0.0)
        assertEquals(1.0, eval("1 0 ||  0 !  &&"), 0.0)
        assertEquals(8.0, SzExpr.parse("HOOKED.w 3 + 4 / 4 *", known).eval(mapOf("HOOKED.w" to 7.0), true), 0.0)
        for (bad in listOf("", "1 +", "1 2", "foo", "HOOKED.x", "1 2 3")) {
            try {
                SzExpr.parse(bad, known); fail("accepted \"$bad\"")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun shipped() {
        val net = WgslNetwork.parse(res("identity.wgsl"))
        assertEquals(1, net.stages.size)
        assertEquals(1, net.active(0.5f).size)
        assertEquals(1, net.active(2f).size)
        assertEquals(0, net.halo(net.active(2f)))
        val plan = net.plan(net.active(2f), 100, 2f)
        assertEquals(100, plan.result.width)
        assertEquals(100, plan.result.height)
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps[0].reads.single() === plan.input)
        assertTrue(plan.steps[0].writes === plan.result)
        assertEquals(1, plan.slots.size)
    }

    @Test
    fun samplers() {
        for (name in listOf("catmull_rom.wgsl", "box_downscale.wgsl")) {
            val code = res(name)
            assertTrue(name, "fn resolve(src_start: vec2<f32>, scale: vec2<f32>)" in code && "//!" !in code)
            fails("a sampler is not a network", code)
        }
    }

    @Test
    fun rejects() {
        WgslNetwork.parse(pass)
        fails("empty", "")
        fails("no hook", pass.replace("//!HOOK MAIN\n", ""))
        fails("workgroup mismatch", pass.replace("(8, 8)", "(16, 16)"))
        fails("too many invocations", pass.replace("COMPUTE 8 8", "COMPUTE 16 16").replace("(8, 8)", "(16, 16)"))
        fails("bind unknown", pass.replace("BIND HOOKED", "BIND nope"))
        fails("bind count", pass.replace("//!BIND HOOKED\n", ""))
        fails("all saved", pass.replace("//!COMPUTE", "//!SAVE x\n//!COMPUTE"))
        fails("save output", pass.replace("//!COMPUTE", "//!SAVE OUTPUT\n//!COMPUTE"))
        fails("no compute", pass.replace("//!COMPUTE 8 8\n", ""))
        fails("group1", pass.replace("@group(0) @binding(0)", "@group(1) @binding(0)"))
        fails("dup idx", pass.replace("@binding(1)", "@binding(0)"))
        fails("read_write", pass.replace(", write>", ", read_write>"))
        fails("format", pass.replace("rgba16float", "r32float"))
        fails("u32", pass.replace("texture_2d<f32>", "texture_2d<u32>"))
        fails("no compute fn", pass.replace("@compute", ""))
        fails("halo", "//!HALO 99999\n$pass")
        fails("halo neg", "//!HALO -1\n$pass")
        fails("unknown directive", pass.replace("//!COMPUTE 8 8", "//!FOO 2\n//!COMPUTE 8 8"))
        fails("bad width", pass.replace("//!COMPUTE", "//!WIDTH HOOKED.w +\n//!COMPUTE"))
    }
}
