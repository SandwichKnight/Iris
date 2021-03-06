package net.coderbot.iris.postprocess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntList;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.program.ProgramBuilder;
import net.coderbot.iris.rendertarget.*;
import net.coderbot.iris.shaderpack.ProgramDirectives;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.uniforms.CommonUniforms;
import org.lwjgl.opengl.GL15C;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.util.Pair;

public class CompositeRenderer {
	private final RenderTargets renderTargets;

	private final ImmutableList<Pass> passes;
	private final GlFramebuffer baseline;

	final CenterDepthSampler centerDepthSampler;

	public CompositeRenderer(ProgramSet pack, RenderTargets renderTargets) {
		centerDepthSampler = new CenterDepthSampler(renderTargets);

		final List<Pair<Program, ProgramDirectives>> programs = new ArrayList<>();

		for (ProgramSource source : pack.getComposite()) {
			if (source == null || !source.isValid()) {
				continue;
			}

			programs.add(createProgram(source));
		}

		pack.getCompositeFinal().map(this::createProgram).ifPresent(programs::add);

		final ImmutableList.Builder<Pass> passes = ImmutableList.builder();

		// Initially filled with false values
		boolean[] stageReadsFromAlt = new boolean[RenderTargets.MAX_RENDER_TARGETS];

		for (Pair<Program, ProgramDirectives> programEntry : programs) {
			Pass pass = new Pass();
			ProgramDirectives directives = programEntry.getRight();

			pass.program = programEntry.getLeft();
			int[] drawBuffers = directives.getDrawBuffers();

			boolean[] stageWritesToAlt = Arrays.copyOf(stageReadsFromAlt, RenderTargets.MAX_RENDER_TARGETS);

			for (int i = 0; i < stageWritesToAlt.length; i++) {
				stageWritesToAlt[i] = !stageWritesToAlt[i];
			}

			GlFramebuffer framebuffer = renderTargets.createColorFramebuffer(stageWritesToAlt, drawBuffers);

			pass.stageReadsFromAlt = Arrays.copyOf(stageReadsFromAlt, stageReadsFromAlt.length);
			pass.framebuffer = framebuffer;
			pass.viewportScale = directives.getViewportScale();

			if (programEntry == programs.get(programs.size() - 1)) {
				pass.isLastPass = true;
			}

			passes.add(pass);

			// Flip the buffers that this shader wrote to
			for (int buffer : drawBuffers) {
				stageReadsFromAlt[buffer] = !stageReadsFromAlt[buffer];
			}
		}

		IntList buffersToBeCleared = pack.getPackDirectives().getBuffersToBeCleared();
		boolean[] willBeCleared = new boolean[RenderTargets.MAX_RENDER_TARGETS];

		buffersToBeCleared.forEach((int buffer) -> {
			willBeCleared[buffer] = true;
		});

		for (int i = 0; i < stageReadsFromAlt.length; i++) {
			if (stageReadsFromAlt[i] && !willBeCleared[i]) {
				Iris.logger.warn("The content of buffer " + i + " needs to be persisted across frames in a way that Iris does not currently support");
			}
		}

		this.passes = passes.build();
		this.renderTargets = renderTargets;

		this.baseline = renderTargets.createFramebufferWritingToMain(new int[] {0});
	}

	private static final class Pass {
		Program program;
		GlFramebuffer framebuffer;
		boolean[] stageReadsFromAlt;
		boolean isLastPass;
		float viewportScale;

		private void destroy() {
			this.program.destroy();
		}
	}

	public void renderAll() {
		centerDepthSampler.endWorldRendering();

		final Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
		final int baseWidth = main.textureWidth;
		final int baseHeight = main.textureHeight;

		// Prepare "static" textures (ones that do not change during gbuffer rendering)
		int depthAttachment = renderTargets.getDepthTexture().getTextureId();
		int depthAttachmentNoTranslucents = renderTargets.getDepthTextureNoTranslucents().getTextureId();

		bindTexture(PostProcessUniforms.DEPTH_TEX_0, depthAttachment);
		bindTexture(PostProcessUniforms.DEPTH_TEX_1, depthAttachmentNoTranslucents);
		// Note: Since we haven't rendered the hand yet, this won't contain any handheld items.
		// Once we start rendering the hand before composite content, this will need to be addressed.
		bindTexture(PostProcessUniforms.DEPTH_TEX_2, depthAttachmentNoTranslucents);

		RenderSystem.activeTexture(GL15C.GL_TEXTURE0 + PostProcessUniforms.NOISE_TEX);
		BuiltinNoiseTexture.bind();

		FullScreenQuadRenderer.INSTANCE.begin();

		for (Pass renderPass : passes) {
			if (!renderPass.isLastPass) {
				renderPass.framebuffer.bind();
			} else {
				main.beginWrite(false);
			}

			bindRenderTarget(PostProcessUniforms.COLOR_TEX_0, renderTargets.get(0), renderPass.stageReadsFromAlt[0]);
			bindRenderTarget(PostProcessUniforms.COLOR_TEX_1, renderTargets.get(1), renderPass.stageReadsFromAlt[1]);
			bindRenderTarget(PostProcessUniforms.COLOR_TEX_2, renderTargets.get(2), renderPass.stageReadsFromAlt[2]);
			bindRenderTarget(PostProcessUniforms.COLOR_TEX_3, renderTargets.get(3), renderPass.stageReadsFromAlt[3]);
			bindRenderTarget(PostProcessUniforms.COLOR_TEX_4, renderTargets.get(4), renderPass.stageReadsFromAlt[4]);
			bindRenderTarget(PostProcessUniforms.COLOR_TEX_5, renderTargets.get(5), renderPass.stageReadsFromAlt[5]);
			bindRenderTarget(PostProcessUniforms.COLOR_TEX_6, renderTargets.get(6), renderPass.stageReadsFromAlt[6]);
			bindRenderTarget(PostProcessUniforms.COLOR_TEX_7, renderTargets.get(7), renderPass.stageReadsFromAlt[7]);

			float scaledWidth = baseWidth * renderPass.viewportScale;
			float scaledHeight = baseHeight * renderPass.viewportScale;
			RenderSystem.viewport(0, 0, (int) scaledWidth, (int) scaledHeight);

			renderPass.program.use();
			FullScreenQuadRenderer.INSTANCE.renderQuad();
		}

		FullScreenQuadRenderer.end();

		if (passes.size() == 0) {
			// If there are no passes, we somehow need to transfer the content of the Iris render targets into the main
			// Minecraft framebuffer.
			//
			// Thus, the following call transfers the content of colortex0 and the depth buffer into the main Minecraft
			// framebuffer.
			FramebufferBlitter.copyFramebufferContent(this.baseline, main);
		} else {
			// We still need to copy the depth buffer content as finalized in the gbuffer pass to the main framebuffer.
			//
			// This is needed for things like on-screen overlays to work properly.
			FramebufferBlitter.copyDepthBufferContent(this.baseline, main);
		}

		// Make sure to reset the viewport to how it was before... Otherwise weird issues could occur.
		// Also bind the "main" framebuffer if it isn't already bound.
		main.beginWrite(true);
		GlStateManager.useProgram(0);

		// TODO: We unbind these textures but it would probably make sense to unbind the other ones too.
		RenderSystem.activeTexture(GL15C.GL_TEXTURE0 + PostProcessUniforms.DEFAULT_DEPTH);
		RenderSystem.bindTexture(0);
		RenderSystem.activeTexture(GL15C.GL_TEXTURE0 + PostProcessUniforms.DEFAULT_COLOR);
		RenderSystem.bindTexture(0);
	}

	private static void bindRenderTarget(int textureUnit, RenderTarget target, boolean readFromAlt) {
		bindTexture(textureUnit, readFromAlt ? target.getAltTexture() : target.getMainTexture());
	}

	private static void bindTexture(int textureUnit, int texture) {
		RenderSystem.activeTexture(GL15C.GL_TEXTURE0 + textureUnit);
		RenderSystem.bindTexture(texture);
	}

	// TODO: Don't just copy this from ShaderPipeline
	private Pair<Program, ProgramDirectives> createProgram(ProgramSource source) {
		// TODO: Properly handle empty shaders
		Objects.requireNonNull(source.getVertexSource());
		Objects.requireNonNull(source.getFragmentSource());
		ProgramBuilder builder;

		try {
			builder = ProgramBuilder.begin(source.getName(), source.getVertexSource().orElse(null),
				source.getFragmentSource().orElse(null));
		} catch (RuntimeException e) {
			// TODO: Better error handling
			throw new RuntimeException("Shader compilation failed!", e);
		}

		CommonUniforms.addCommonUniforms(builder, source.getParent().getPack().getIdMap());
		PostProcessUniforms.addPostProcessUniforms(builder, this);

		return new Pair<>(builder.build(), source.getDirectives());
	}

	public void destroy() {
		for (Pass renderPass : passes) {
			renderPass.destroy();
		}
	}
}
