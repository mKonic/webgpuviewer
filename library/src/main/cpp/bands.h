/* Splitting a pass over an image into horizontal bands, a thread each.
 *
 * Every pass that uses this writes only the rows of its own band and reads only
 * what nothing writes, so bands need no locking and the result is the same
 * however many there were. Worker threads never touch JNI, so they need no
 * JNIEnv of their own. */

#pragma once

#include <algorithm>
#include <cstdint>
#include <thread>
#include <vector>

/* Threads worth spending on a `width` x `height` pass. */
inline int chooseThreadCount(int width, int height) {
  // Below roughly a quarter-megapixel the pass is short enough that spawning
  // threads costs more than it saves.
  if (static_cast<int64_t>(width) * height < 256 * 1024) {
    return 1;
  }
  unsigned hardware = std::thread::hardware_concurrency();
  int count = static_cast<int>(std::min(hardware ? hardware : 1u, 8u));
  // Keep bands big enough to be worth a thread.
  count = std::min(count, height / 64);
  return std::max(1, count);
}

/* Runs `band(y0, y1)` over [0, rows) in `count` contiguous bands, the first on
 * the calling thread, and returns once every band has. */
template <typename Band>
void forEachBand(int rows, int count, const Band &band) {
  if (count <= 1) {
    band(0, rows);
    return;
  }

  const int rowsPerBand = (rows + count - 1) / count;
  std::vector<std::thread> workers;
  workers.reserve(count - 1);

  for (int t = 1; t < count; ++t) {
    const int y0 = std::min(t * rowsPerBand, rows);
    const int y1 = std::min(y0 + rowsPerBand, rows);
    if (y0 >= y1) {
      continue;
    }
    workers.emplace_back([=, &band] { band(y0, y1); });
  }

  band(0, std::min(rowsPerBand, rows));

  for (auto &worker : workers) {
    worker.join();
  }
}
