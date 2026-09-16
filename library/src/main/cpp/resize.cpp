/* Half-resolution area resize of RGBA8 in linear light, for an image's
 * mipmaps. Runs once per level on every page load, so it is the part of a load
 * that grows with the page. */

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <vector>

#include "bands.h"

namespace {

float srgbToLinearLUT[256];

/* Each alpha byte over 255, so the division is not repeated per sample. */
float alphaLUT[256];

/* See encodeSrgb. A power of two, so scaling a value into a bin is exact. */
constexpr int kEncodeBins = 4096;

/* [level] is the smallest float that linearToSRGBExact takes to it. Level 0
 * needs none and 256 is never reached. */
float encodeThreshold[257];

/* The level each bin's lower edge encodes to. */
uint8_t encodeBinLevel[kEncodeBins];

std::once_flag lutsOnceFlag;

inline uint8_t linearToSRGBExact(float linearVal) {
  if (linearVal <= 0.0f)
    return 0;
  if (linearVal >= 1.0f)
    return 255;

  float srgb01 = (linearVal <= 0.0031308f)
                     ? (linearVal * 12.92f)
                     : (1.055f * std::pow(linearVal, 1.0f / 2.4f) - 0.055f);

  return static_cast<uint8_t>(std::roundf(srgb01 * 255.0f));
}

inline uint8_t exactLinearToAlpha(float linearAlpha) {
  if (linearAlpha <= 0.0f)
    return 0;
  if (linearAlpha >= 1.0f)
    return 255;
  return static_cast<uint8_t>(std::roundf(linearAlpha * 255.0f));
}

/* The smallest float in (0, 1] that linearToSRGBExact takes to at least
 * [level]. Bisected over the bit patterns, which for non-negative floats run in
 * the same order as the values. */
float firstLinearReaching(int level) {
  uint32_t lo = 0;           /* 0.0f, which encodes to 0 */
  uint32_t hi = 0x3F800000u; /* 1.0f, which encodes to 255 */
  while (hi - lo > 1) {
    const uint32_t mid = lo + (hi - lo) / 2;
    float value;
    std::memcpy(&value, &mid, sizeof value);
    if (linearToSRGBExact(value) >= level)
      hi = mid;
    else
      lo = mid;
  }
  float value;
  std::memcpy(&value, &hi, sizeof value);
  return value;
}

/* A bare bool flag races: two mipmap levels can resize concurrently on first
 * use. */
void initLUTs() {
  std::call_once(lutsOnceFlag, [] {
    for (int i = 0; i < 256; i++) {
      float val01 = i / 255.0f;
      srgbToLinearLUT[i] = (val01 <= 0.04045f)
                               ? (val01 / 12.92f)
                               : std::pow((val01 + 0.055f) / 1.055f, 2.4f);
      alphaLUT[i] = i / 255.0f;
    }

    encodeThreshold[0] = 0.0f;
    for (int level = 1; level < 256; ++level)
      encodeThreshold[level] = firstLinearReaching(level);
    encodeThreshold[256] = INFINITY;

    int level = 0;
    for (int bin = 0; bin < kEncodeBins; ++bin) {
      const float edge = static_cast<float>(bin) / kEncodeBins;
      while (level < 255 && encodeThreshold[level + 1] <= edge)
        ++level;
      encodeBinLevel[bin] = static_cast<uint8_t>(level);
    }
  });
}

/* linearToSRGBExact without its pow, which was most of the resize: the level is
 * the highest whose threshold [linearVal] reaches. Its bin's lower edge gives a
 * level at most one short, since the steepest part of the curve moves under one
 * level per bin, and the loop steps the rest. */
inline uint8_t encodeSrgb(float linearVal) {
  if (linearVal <= 0.0f)
    return 0;
  if (linearVal >= 1.0f)
    return 255;
  int level = encodeBinLevel[static_cast<int>(linearVal * kEncodeBins)];
  while (encodeThreshold[level + 1] <= linearVal)
    ++level;
  return static_cast<uint8_t>(level);
}

/* The source columns (or rows) each destination column averages, with their
 * area weights. Built once per axis rather than per pixel, and only with
 * positive weights, which is all a sample loop ever used. */
struct Axis {
  /* Destination index i's samples are [first[i], first[i + 1]). */
  std::vector<int> first;
  std::vector<int> source;
  std::vector<double> weight;
};

Axis buildAxis(int srcSize, int dstSize) {
  Axis axis;
  axis.first.reserve(dstSize + 1);
  const double scale = (double)srcSize / dstSize;

  for (int d = 0; d < dstSize; ++d) {
    axis.first.push_back((int)axis.source.size());
    double start = d * scale;
    double end = start + scale;
    int lo = std::max(0, (int)start);
    int hi = std::min(srcSize - 1, (int)end);

    for (int s = lo; s <= hi; ++s) {
      double weight =
          std::min((double)s + 1.0, end) - std::max((double)s, start);
      if (weight <= 0)
        continue;
      axis.source.push_back(s);
      axis.weight.push_back(weight);
    }
  }

  axis.first.push_back((int)axis.source.size());
  return axis;
}

/* Rows [y0, y1) of the destination. The arithmetic is the per-pixel resize this
 * replaced, statement for statement, so a compiler that fuses a multiply into
 * the add after it does so in the same places and every output byte stays the
 * same. */
void resizeRows(const uint32_t *src, uint32_t *dst, int srcWidth,
                int dstWidth, const Axis &xs, const Axis &ys, int y0, int y1) {
  for (int y = y0; y < y1; ++y) {
    for (int x = 0; x < dstWidth; ++x) {
      float sumA = 0.0f, sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
      float totalWeight = 0.0f;

      for (int j = ys.first[y]; j < ys.first[y + 1]; ++j) {
        double yWeight = ys.weight[j];
        const uint32_t *row = src + (size_t)ys.source[j] * srcWidth;

        for (int i = xs.first[x]; i < xs.first[x + 1]; ++i) {
          float pWeight = (float)(xs.weight[i] * yWeight);
          totalWeight += pWeight;

          uint32_t pixel = row[xs.source[i]];
          float aVal = alphaLUT[(pixel >> 24) & 0xFF];
          float rVal = srgbToLinearLUT[(pixel >> 16) & 0xFF] * aVal;
          float gVal = srgbToLinearLUT[(pixel >> 8) & 0xFF] * aVal;
          float bVal = srgbToLinearLUT[pixel & 0xFF] * aVal;

          sumA += aVal * pWeight;
          sumR += rVal * pWeight;
          sumG += gVal * pWeight;
          sumB += bVal * pWeight;
        }
      }

      uint32_t finalA = 0, finalR = 0, finalG = 0, finalB = 0;

      if (totalWeight > 0.0f) {
        float invWeight = 1.0f / totalWeight;
        float finalLinearA = sumA * invWeight;
        float finalLinearR = sumR * invWeight;
        float finalLinearG = sumG * invWeight;
        float finalLinearB = sumB * invWeight;

        if (finalLinearA > 0.00001f) {
          float invAlpha = 1.0f / finalLinearA;
          finalLinearR = std::min(1.0f, finalLinearR * invAlpha);
          finalLinearG = std::min(1.0f, finalLinearG * invAlpha);
          finalLinearB = std::min(1.0f, finalLinearB * invAlpha);
        } else {
          finalLinearR = 0.0f;
          finalLinearG = 0.0f;
          finalLinearB = 0.0f;
        }

        finalA = exactLinearToAlpha(finalLinearA);
        finalR = encodeSrgb(finalLinearR);
        finalG = encodeSrgb(finalLinearG);
        finalB = encodeSrgb(finalLinearB);
      }

      dst[(size_t)y * dstWidth + x] =
          (finalA << 24) | (finalR << 16) | (finalG << 8) | finalB;
    }
  }
}

/* [src] is [srcWidth] x [srcHeight]; [dst] holds half of each, rounded down. */
void resizeLinearArea(const uint32_t *src, uint32_t *dst, int srcWidth,
                      int srcHeight) {
  initLUTs();
  const int dstWidth = srcWidth / 2;
  const int dstHeight = srcHeight / 2;
  const Axis xs = buildAxis(srcWidth, dstWidth);
  const Axis ys = buildAxis(srcHeight, dstHeight);

  forEachBand(dstHeight, chooseThreadCount(dstWidth, dstHeight),
              [&](int y0, int y1) {
                resizeRows(src, dst, srcWidth, dstWidth, xs, ys, y0, y1);
              });
}

} // namespace

/* A mismatched size/buffer here indexes past its end rather than throwing, so
 * both are checked. */
extern "C" JNIEXPORT void JNICALL
Java_ca_mpreg_webgpuviewer_ImageUtil_resizeLinearAreaNative(
    JNIEnv *env, jobject thiz, jobject src_buffer, jobject dst_buffer,
    jint srcWidth, jint srcHeight) {
  if (!env || !src_buffer || !dst_buffer)
    return;
  if (srcWidth <= 0 || srcHeight <= 0 || srcWidth > 16384 || srcHeight > 16384)
    return;

  uint32_t *src = (uint32_t *)env->GetDirectBufferAddress(src_buffer);
  uint32_t *dst = (uint32_t *)env->GetDirectBufferAddress(dst_buffer);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return;
  }
  if (!src || !dst)
    return;

  /* 64-bit: a 32-bit product wraps for a large enough image. */
  const jlong srcCapacity = env->GetDirectBufferCapacity(src_buffer);
  const jlong srcNeeded =
      static_cast<jlong>(srcWidth) * static_cast<jlong>(srcHeight) * 4LL;
  if (srcCapacity < 0 || srcCapacity < srcNeeded)
    return;

  int dstWidth = srcWidth / 2;
  int dstHeight = srcHeight / 2;
  if (dstWidth <= 0 || dstHeight <= 0)
    return;

  const jlong dstCapacity = env->GetDirectBufferCapacity(dst_buffer);
  const jlong dstNeeded =
      static_cast<jlong>(dstWidth) * static_cast<jlong>(dstHeight) * 4LL;
  if (dstCapacity < 0 || dstCapacity < dstNeeded)
    return;

  resizeLinearArea(src, dst, srcWidth, srcHeight);
}
