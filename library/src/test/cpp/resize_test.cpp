// Tests for the mipmap resize.
//
// The resize was rewritten for speed, and a mip level that came out a shade different would never
// be noticed by eye - so the contract is exact: every byte matches the per-pixel implementation it
// replaced, kept below as the reference. The implementation is included rather than linked for the
// reason given in trim_test.cpp.

#include "resize.cpp" // NOLINT(bugprone-suspicious-include)

#include "check.h"

#include <cstring>
#include <random>
#include <vector>

namespace {

/**
 * The resize as it was: area weights worked out per sample and a pow per channel per pixel. On
 * ARM this was the scalar loop after the NEON one, which never ran - a half-resolution pixel spans
 * at most three source columns, and the NEON loop wanted four.
 */
void referenceResize(const uint32_t *src, uint32_t *dst, int srcWidth, int srcHeight) {
  initLUTs();
  int dstWidth = srcWidth / 2;
  int dstHeight = srcHeight / 2;
  double scaleX = (double)srcWidth / dstWidth;
  double scaleY = (double)srcHeight / dstHeight;

  for (int y = 0; y < dstHeight; ++y) {
    double srcYStart = y * scaleY;
    double srcYEnd = srcYStart + scaleY;
    int yMin = std::max(0, (int)srcYStart);
    int yMax = std::min(srcHeight - 1, (int)srcYEnd);

    for (int x = 0; x < dstWidth; ++x) {
      double srcXStart = x * scaleX;
      double srcXEnd = srcXStart + scaleX;
      int xMin = std::max(0, (int)srcXStart);
      int xMax = std::min(srcWidth - 1, (int)srcXEnd);
      CHECK(xMax - xMin <= 2);

      uint32_t finalA = 0, finalR = 0, finalG = 0, finalB = 0;
      float totalWeight = 0.0f;
      float sumA = 0.0f, sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;

      for (int sy = yMin; sy <= yMax; ++sy) {
        double yWeight = std::min((double)sy + 1.0, srcYEnd) - std::max((double)sy, srcYStart);
        if (yWeight <= 0) continue;

        for (int sx = xMin; sx <= xMax; ++sx) {
          double xWeight = std::min((double)sx + 1.0, srcXEnd) - std::max((double)sx, srcXStart);
          if (xWeight <= 0) continue;

          float pWeight = (float)(xWeight * yWeight);
          totalWeight += pWeight;

          uint32_t pixel = src[sy * srcWidth + sx];
          float aVal = ((pixel >> 24) & 0xFF) / 255.0f;
          float rVal = srgbToLinearLUT[(pixel >> 16) & 0xFF] * aVal;
          float gVal = srgbToLinearLUT[(pixel >> 8) & 0xFF] * aVal;
          float bVal = srgbToLinearLUT[pixel & 0xFF] * aVal;

          sumA += aVal * pWeight;
          sumR += rVal * pWeight;
          sumG += gVal * pWeight;
          sumB += bVal * pWeight;
        }
      }

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
        finalR = linearToSRGBExact(finalLinearR);
        finalG = linearToSRGBExact(finalLinearG);
        finalB = linearToSRGBExact(finalLinearB);
      }

      dst[y * dstWidth + x] = (finalA << 24) | (finalR << 16) | (finalG << 8) | finalB;
    }
  }
}

/**
 * Random pixels, with alpha drawn so that fully transparent, fully opaque and everything between
 * all turn up - transparency is where the division by alpha lives.
 */
std::vector<uint32_t> noise(int width, int height, uint32_t seed) {
  std::mt19937 rng(seed);
  std::vector<uint32_t> px(static_cast<size_t>(width) * height);
  for (uint32_t &p : px) {
    uint32_t v = rng();
    const uint32_t pick = rng() % 4;
    const uint32_t alpha = pick == 0 ? 0 : pick == 1 ? 255 : (v >> 24);
    p = (alpha << 24) | (v & 0xFFFFFF);
  }
  return px;
}

int countMismatches(int width, int height, uint32_t seed) {
  const std::vector<uint32_t> src = noise(width, height, seed);
  std::vector<uint32_t> expected(static_cast<size_t>(width / 2) * (height / 2));
  std::vector<uint32_t> actual(expected.size(), 0xDEADBEEF);
  referenceResize(src.data(), expected.data(), width, height);
  resizeLinearArea(src.data(), actual.data(), width, height);
  int mismatches = 0;
  for (size_t i = 0; i < expected.size(); ++i) {
    if (expected[i] != actual[i]) ++mismatches;
  }
  return mismatches;
}

float fromBits(uint32_t bits) {
  float f;
  std::memcpy(&f, &bits, sizeof f);
  return f;
}

uint32_t toBits(float f) {
  uint32_t bits;
  std::memcpy(&bits, &f, sizeof bits);
  return bits;
}

} // namespace

// --- encodeSrgb --------------------------------------------------------------------------------

TEST(encodeMatchesThePowAroundEveryLevelBoundary) {
  initLUTs();
  // Where the table could go wrong is right at a boundary, so every float near each one.
  int mismatches = 0;
  for (int level = 1; level < 256; ++level) {
    const uint32_t centre = toBits(encodeThreshold[level]);
    for (uint32_t bits = centre - 4096; bits <= centre + 4096; ++bits) {
      const float v = fromBits(bits);
      if (encodeSrgb(v) != linearToSRGBExact(v)) ++mismatches;
    }
  }
  CHECK_EQ(mismatches, 0);
}

TEST(encodeMatchesThePowAcrossTheRange) {
  initLUTs();
  int mismatches = 0;
  // Every 64th float from 0 up to 1, bin edges and the linear toe included.
  for (uint32_t bits = 0; bits <= toBits(1.0f); bits += 64) {
    const float v = fromBits(bits);
    if (encodeSrgb(v) != linearToSRGBExact(v)) ++mismatches;
  }
  for (int bin = 0; bin <= kEncodeBins; ++bin) {
    const float edge = static_cast<float>(bin) / kEncodeBins;
    for (float v : {std::nextafter(edge, 0.0f), edge, std::nextafter(edge, 2.0f)}) {
      if (encodeSrgb(v) != linearToSRGBExact(v)) ++mismatches;
    }
  }
  CHECK_EQ(mismatches, 0);
}

TEST(encodeClampsOutsideTheUnitRange) {
  initLUTs();
  CHECK_EQ(static_cast<int>(encodeSrgb(-1.0f)), 0);
  CHECK_EQ(static_cast<int>(encodeSrgb(0.0f)), 0);
  CHECK_EQ(static_cast<int>(encodeSrgb(1.0f)), 255);
  CHECK_EQ(static_cast<int>(encodeSrgb(7.0f)), 255);
}

// --- resizeLinearArea --------------------------------------------------------------------------

TEST(resizeMatchesTheReferenceOnSmallAndOddSizes) {
  int mismatches = 0;
  const int sizes[][2] = {{2, 2}, {3, 3}, {2, 5}, {5, 2}, {7, 9}, {16, 16}, {33, 17}, {255, 257}};
  uint32_t seed = 1;
  for (const auto &size : sizes) mismatches += countMismatches(size[0], size[1], seed++);
  CHECK_EQ(mismatches, 0);
}

TEST(resizeMatchesTheReferenceAcrossThreadBands) {
  // Large enough to split into bands, with odd sides so the last row and column take the
  // fractional weights.
  CHECK(chooseThreadCount(1025 / 2, 1203 / 2) > 1 || std::thread::hardware_concurrency() <= 1);
  CHECK_EQ(countMismatches(1025, 1203, 42), 0);
  CHECK_EQ(countMismatches(1024, 1200, 43), 0);
}

TEST(resizeKeepsAnOpaqueColourExactly) {
  const int w = 64, h = 64;
  std::vector<uint32_t> src(static_cast<size_t>(w) * h, 0xFF336699u);
  std::vector<uint32_t> dst(static_cast<size_t>(w / 2) * (h / 2));
  resizeLinearArea(src.data(), dst.data(), w, h);
  int wrong = 0;
  for (uint32_t p : dst) {
    if (p != 0xFF336699u) ++wrong;
  }
  CHECK_EQ(wrong, 0);
}

int main() { return check::main(); }
