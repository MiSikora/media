/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.formatInvariant;
import static androidx.media3.common.util.Util.loadAsset;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
import android.opengl.GLES20;
import android.util.SparseArray;
import android.util.SparseIntArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.IOException;

/** Applies zero or more {@link TextureOverlay}s onto each frame. */
/* package */ final class OverlayShaderProgram extends BaseGlShaderProgram {

  private static final String ULTRA_HDR_INSERT = "shaders/insert_ultra_hdr.glsl";

  private final GlProgram glProgram;
  private final SamplerOverlayMatrixProvider samplerOverlayMatrixProvider;
  private final ImmutableList<TextureOverlay> overlays;
  private final boolean useHdr;
  private final SparseArray<Gainmap> lastGainmaps;
  private final SparseIntArray gainmapTexIds;

  /**
   * Creates a new instance.
   *
   * @param context The {@link Context}
   * @param useHdr Whether input textures come from an HDR source. If {@code true}, colors will be
   *     in linear RGB BT.2020. If {@code false}, colors will be in linear RGB BT.709. useHdr is
   *     only supported on API 34+ for {@link BitmapOverlay}s, where the {@link Bitmap} contains a
   *     {@link Gainmap}.
   * @throws VideoFrameProcessingException If a problem occurs while reading shader files.
   */
  public OverlayShaderProgram(
      Context context, boolean useHdr, ImmutableList<TextureOverlay> overlays)
      throws VideoFrameProcessingException {
    super(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1);
    if (useHdr) {
      // Each UltraHDR overlay uses an extra texture to apply the gainmap to the base in the shader.
      checkArgument(
          overlays.size() <= 7,
          "OverlayShaderProgram does not support more than 7 HDR overlays in the same instance.");
      checkArgument(Util.SDK_INT >= 34);
    } else {
      // The maximum number of samplers allowed in a single GL program is 16.
      // We use one for every overlay and one for the video.
      checkArgument(
          overlays.size() <= 15,
          "OverlayShaderProgram does not support more than 15 SDR overlays in the same instance.");
    }

    this.useHdr = useHdr;
    this.overlays = overlays;
    this.samplerOverlayMatrixProvider = new SamplerOverlayMatrixProvider();
    lastGainmaps = new SparseArray<>();
    gainmapTexIds = new SparseIntArray();
    try {
      glProgram =
          new GlProgram(
              createVertexShader(overlays.size()),
              createFragmentShader(context, overlays.size(), useHdr));
    } catch (GlUtil.GlException | IOException e) {
      throw new VideoFrameProcessingException(e);
    }

    glProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
  }

  @Override
  public Size configure(int inputWidth, int inputHeight) {
    Size videoSize = new Size(inputWidth, inputHeight);
    samplerOverlayMatrixProvider.configure(/* backgroundSize= */ videoSize);
    for (TextureOverlay overlay : overlays) {
      overlay.configure(videoSize);
    }
    return videoSize;
  }

  @Override
  @SuppressLint("NewApi") // Checked API level in constructor
  public void drawFrame(int inputTexId, long presentationTimeUs)
      throws VideoFrameProcessingException {
    try {
      glProgram.use();
      for (int texUnitIndex = 1; texUnitIndex <= overlays.size(); texUnitIndex++) {
        TextureOverlay overlay = overlays.get(texUnitIndex - 1);

        if (useHdr) {
          checkArgument(overlay instanceof BitmapOverlay);
          Bitmap bitmap = ((BitmapOverlay) overlay).getBitmap(presentationTimeUs);
          checkArgument(bitmap.hasGainmap());
          Gainmap gainmap = checkNotNull(bitmap.getGainmap());
          @Nullable Gainmap lastGainmap = lastGainmaps.get(texUnitIndex);
          if (lastGainmap == null || !GainmapUtil.equals(lastGainmap, gainmap)) {
            lastGainmaps.put(texUnitIndex, gainmap);
            if (gainmapTexIds.get(texUnitIndex, /* valueIfKeyNotFound= */ C.INDEX_UNSET)
                == C.INDEX_UNSET) {
              gainmapTexIds.put(texUnitIndex, GlUtil.createTexture(gainmap.getGainmapContents()));
            } else {
              GlUtil.setTexture(gainmapTexIds.get(texUnitIndex), gainmap.getGainmapContents());
            }
            glProgram.setSamplerTexIdUniform(
                "uGainmapTexSampler" + texUnitIndex, gainmapTexIds.get(texUnitIndex), texUnitIndex);
            GainmapUtil.setGainmapUniforms(glProgram, lastGainmaps.get(texUnitIndex), texUnitIndex);
          }
        }

        glProgram.setSamplerTexIdUniform(
            formatInvariant("uOverlayTexSampler%d", texUnitIndex),
            overlay.getTextureId(presentationTimeUs),
            texUnitIndex);
        glProgram.setFloatsUniform(
            formatInvariant("uVertexTransformationMatrix%d", texUnitIndex),
            overlay.getVertexTransformation(presentationTimeUs));
        OverlaySettings overlaySettings = overlay.getOverlaySettings(presentationTimeUs);
        Size overlaySize = overlay.getTextureSize(presentationTimeUs);
        glProgram.setFloatsUniform(
            formatInvariant("uTransformationMatrix%d", texUnitIndex),
            samplerOverlayMatrixProvider.getTransformationMatrix(overlaySize, overlaySettings));
        glProgram.setFloatUniform(
            formatInvariant("uOverlayAlphaScale%d", texUnitIndex), overlaySettings.alphaScale);
      }

      glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, /* texUnitIndex= */ 0);
      glProgram.bindAttributesAndUniforms();
      // The four-vertex triangle strip forms a quad.
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      GlUtil.checkGlError();
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e, presentationTimeUs);
    }
  }

  @Override
  public void release() throws VideoFrameProcessingException {
    super.release();
    try {
      glProgram.delete();
      for (int i = 0; i < overlays.size(); i++) {
        overlays.get(i).release();
        if (useHdr) {
          int gainmapTexId = gainmapTexIds.get(i, /* valueIfKeyNotFound= */ C.INDEX_UNSET);
          if (gainmapTexId != C.INDEX_UNSET) {
            GlUtil.deleteTexture(gainmapTexId);
          }
        }
      }
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }
  }

  private static String createVertexShader(int numOverlays) {
    StringBuilder shader =
        new StringBuilder()
            .append("#version 100\n")
            .append("attribute vec4 aFramePosition;\n")
            .append("varying vec2 vVideoTexSamplingCoord0;\n");

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(formatInvariant("uniform mat4 uTransformationMatrix%s;\n", texUnitIndex))
          .append(formatInvariant("uniform mat4 uVertexTransformationMatrix%s;\n", texUnitIndex))
          .append(formatInvariant("varying vec2 vOverlayTexSamplingCoord%s;\n", texUnitIndex));
    }

    shader
        .append("vec2 getTexSamplingCoord(vec2 ndcPosition){\n")
        .append("  return vec2(ndcPosition.x * 0.5 + 0.5, ndcPosition.y * 0.5 + 0.5);\n")
        .append("}\n")
        .append("void main() {\n")
        .append("  gl_Position = aFramePosition;\n")
        .append("  vVideoTexSamplingCoord0 = getTexSamplingCoord(aFramePosition.xy);\n");

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(formatInvariant("  vec4 aOverlayPosition%d = \n", texUnitIndex))
          .append(
              formatInvariant(
                  "  uVertexTransformationMatrix%s * uTransformationMatrix%s * aFramePosition;\n",
                  texUnitIndex, texUnitIndex))
          .append(
              formatInvariant(
                  "  vOverlayTexSamplingCoord%d = getTexSamplingCoord(aOverlayPosition%d.xy);\n",
                  texUnitIndex, texUnitIndex));
    }

    shader.append("}\n");

    return shader.toString();
  }

  private static String createFragmentShader(Context context, int numOverlays, boolean useHdr)
      throws IOException {
    StringBuilder shader =
        new StringBuilder()
            .append("#version 100\n")
            .append("precision mediump float;\n")
            .append("uniform sampler2D uVideoTexSampler0;\n")
            .append("varying vec2 vVideoTexSamplingCoord0;\n")
            .append("\n")
            .append("// Manually implementing the CLAMP_TO_BORDER texture wrapping option\n")
            .append(
                "// (https://open.gl/textures) since it's not implemented until OpenGL ES 3.2.\n")
            .append("vec4 getClampToBorderOverlayColor(\n")
            .append("    sampler2D texSampler, vec2 texSamplingCoord, float alphaScale){\n")
            .append("  if (texSamplingCoord.x > 1.0 || texSamplingCoord.x < 0.0\n")
            .append("      || texSamplingCoord.y > 1.0 || texSamplingCoord.y < 0.0) {\n")
            .append("    return vec4(0.0, 0.0, 0.0, 0.0);\n")
            .append("  } else {\n")
            .append("    vec4 overlayColor = vec4(texture2D(texSampler, texSamplingCoord));\n")
            .append("    overlayColor.a = alphaScale * overlayColor.a;\n")
            .append("    return overlayColor;\n")
            .append("  }\n")
            .append("}\n")
            .append("\n")
            .append("vec4 getMixColor(vec4 videoColor, vec4 overlayColor) {\n")
            .append("  vec4 outputColor;\n")
            .append("  outputColor.rgb = overlayColor.rgb * overlayColor.a\n")
            .append("      + videoColor.rgb * (1.0 - overlayColor.a);\n")
            .append("  outputColor.a = overlayColor.a + videoColor.a * (1.0 - overlayColor.a);\n")
            .append("  return outputColor;\n")
            .append("}\n")
            .append("\n");

    if (useHdr) {
      shader.append(loadAsset(context, ULTRA_HDR_INSERT));
    }

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(formatInvariant("uniform sampler2D uOverlayTexSampler%d;\n", texUnitIndex))
          .append(formatInvariant("uniform float uOverlayAlphaScale%d;\n", texUnitIndex))
          .append(formatInvariant("varying vec2 vOverlayTexSamplingCoord%d;\n", texUnitIndex))
          .append("\n");
      if (useHdr) {
        shader
            .append("// Uniforms for applying the gainmap to the base.\n")
            .append(formatInvariant("uniform sampler2D uGainmapTexSampler%d;\n", texUnitIndex))
            .append(formatInvariant("uniform int uGainmapIsAlpha%d;\n", texUnitIndex))
            .append(formatInvariant("uniform int uNoGamma%d;\n", texUnitIndex))
            .append(formatInvariant("uniform int uSingleChannel%d;\n", texUnitIndex))
            .append(formatInvariant("uniform vec4 uLogRatioMin%d;\n", texUnitIndex))
            .append(formatInvariant("uniform vec4 uLogRatioMax%d;\n", texUnitIndex))
            .append(formatInvariant("uniform vec4 uEpsilonSdr%d;\n", texUnitIndex))
            .append(formatInvariant("uniform vec4 uEpsilonHdr%d;\n", texUnitIndex))
            .append(formatInvariant("uniform vec4 uGainmapGamma%d;\n", texUnitIndex))
            .append(formatInvariant("uniform float uDisplayRatioHdr%d;\n", texUnitIndex))
            .append(formatInvariant("uniform float uDisplayRatioSdr%d;\n", texUnitIndex))
            .append("\n");
      }
    }

    shader
        .append("void main() {\n")
        .append(
            "  vec4 videoColor = vec4(texture2D(uVideoTexSampler0, vVideoTexSamplingCoord0));\n")
        .append("  vec4 fragColor = videoColor;\n");

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(
              formatInvariant(
                  "  vec4 electricalOverlayColor%d = getClampToBorderOverlayColor(\n",
                  texUnitIndex))
          .append(
              formatInvariant(
                  "    uOverlayTexSampler%d, vOverlayTexSamplingCoord%d, uOverlayAlphaScale%d);\n",
                  texUnitIndex, texUnitIndex, texUnitIndex));
      String overlayMixColor = "electricalOverlayColor";
      if (useHdr) {
        shader
            .append(
                formatInvariant(
                    "  vec4 gainmap%d = texture2D(uGainmapTexSampler%d,"
                        + " vOverlayTexSamplingCoord%d);\n",
                    texUnitIndex, texUnitIndex, texUnitIndex))
            .append(formatInvariant("  vec3 opticalBt709Color%d = applyGainmap(\n", texUnitIndex))
            .append(
                formatInvariant(
                    "      srgbEotf(electricalOverlayColor%d), gainmap%d, uGainmapIsAlpha%d,\n",
                    texUnitIndex, texUnitIndex, texUnitIndex))
            .append(
                formatInvariant(
                    "      uNoGamma%d, uSingleChannel%d, uLogRatioMin%d, uLogRatioMax%d,"
                        + " uEpsilonSdr%d,\n",
                    texUnitIndex, texUnitIndex, texUnitIndex, texUnitIndex, texUnitIndex))
            .append(
                formatInvariant(
                    "      uEpsilonHdr%d, uGainmapGamma%d, uDisplayRatioHdr%d,"
                        + " uDisplayRatioSdr%d);\n",
                    texUnitIndex, texUnitIndex, texUnitIndex, texUnitIndex))
            .append(formatInvariant("  vec4 opticalBt2020OverlayColor%d =\n", texUnitIndex))
            .append(
                formatInvariant(
                    "      vec4(scaleHdrLuminance(bt709ToBt2020(opticalBt709Color%d)),"
                        + " electricalOverlayColor%d.a);\n",
                    texUnitIndex, texUnitIndex));
        overlayMixColor = "opticalBt2020OverlayColor";
      }
      shader.append(
          formatInvariant(
              "  fragColor = getMixColor(fragColor, %s%d);\n", overlayMixColor, texUnitIndex));
    }

    shader.append("  gl_FragColor = fragColor;\n").append("}\n");

    return shader.toString();
  }
}
