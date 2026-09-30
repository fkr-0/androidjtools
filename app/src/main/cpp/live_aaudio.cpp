#include <aaudio/AAudio.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

namespace {

constexpr int kDeckCount = 2;

struct DeckRing {
  std::vector<float> samples;
  size_t capacity = 0;
  std::atomic<uint64_t> read_index{0};
  std::atomic<uint64_t> write_index{0};
  std::atomic<int64_t> underrun_frames{0};
  std::atomic<int64_t> dropped_samples{0};
  std::atomic<float> gain{1.0f};
  std::atomic<bool> playing{false};
};

class AAudioMixer {
 public:
  AAudioMixer(int32_t sample_rate, int32_t channel_count)
      : sample_rate_(sample_rate), channel_count_(channel_count) {}

  ~AAudioMixer() { close(); }

  bool open() {
    if (stream_ != nullptr) return true;
    if (!openWithSharingMode(AAUDIO_SHARING_MODE_EXCLUSIVE) &&
        !openWithSharingMode(AAUDIO_SHARING_MODE_SHARED)) {
      return false;
    }

    const int32_t burst = std::max(AAudioStream_getFramesPerBurst(stream_), 1);
    frames_per_burst_.store(burst, std::memory_order_relaxed);
    AAudioStream_setBufferSizeInFrames(stream_, burst * 2);

    const int32_t ring_frames = std::max(burst * 8, sample_rate_ / 50);
    const size_t ring_samples =
        static_cast<size_t>(ring_frames * channel_count_);
    for (auto& deck : decks_) {
      deck.samples.assign(ring_samples, 0.0f);
      deck.capacity = ring_samples;
    }
    return ring_samples > 0;
  }

  bool setDeckPlaying(int deck_index, bool playing) {
    DeckRing* deck = deckAt(deck_index);
    if (deck == nullptr || stream_ == nullptr) return false;
    deck->playing.store(playing, std::memory_order_release);

    if (playing) {
      const aaudio_stream_state_t state = AAudioStream_getState(stream_);
      if (state == AAUDIO_STREAM_STATE_STARTED ||
          state == AAUDIO_STREAM_STATE_STARTING) {
        return true;
      }
      return AAudioStream_requestStart(stream_) == AAUDIO_OK;
    }

    if (!anyDeckPlaying()) {
      AAudioStream_requestPause(stream_);
    }
    return true;
  }

  void setDeckGain(int deck_index, float gain) {
    if (DeckRing* deck = deckAt(deck_index)) {
      deck->gain.store(std::clamp(gain, 0.0f, 1.0f),
                       std::memory_order_release);
    }
  }

  void flushDeck(int deck_index) {
    if (DeckRing* deck = deckAt(deck_index)) {
      deck->read_index.store(
          deck->write_index.load(std::memory_order_acquire),
          std::memory_order_release);
    }
  }

  int32_t writeDeck(int deck_index, const float* samples, int32_t sample_count) {
    DeckRing* deck = deckAt(deck_index);
    if (deck == nullptr || samples == nullptr || sample_count <= 0 ||
        deck->capacity == 0) {
      return 0;
    }

    const uint64_t write =
        deck->write_index.load(std::memory_order_relaxed);
    const uint64_t read =
        deck->read_index.load(std::memory_order_acquire);
    const uint64_t used = write - read;
    const uint64_t free_samples =
        used >= deck->capacity ? 0 : deck->capacity - used;
    const int32_t accepted = static_cast<int32_t>(
        std::min<uint64_t>(free_samples, static_cast<uint64_t>(sample_count)));

    for (int32_t i = 0; i < accepted; ++i) {
      deck->samples[static_cast<size_t>((write + i) % deck->capacity)] =
          samples[i];
    }
    deck->write_index.store(write + accepted, std::memory_order_release);

    if (accepted < sample_count) {
      deck->dropped_samples.fetch_add(
          sample_count - accepted, std::memory_order_relaxed);
    }
    return accepted;
  }

  int64_t framesPerBurst() const {
    return frames_per_burst_.load(std::memory_order_relaxed);
  }

  int64_t bufferSizeFrames() const {
    return stream_ == nullptr ? 0 : AAudioStream_getBufferSizeInFrames(stream_);
  }

  int64_t xRunCount() const {
    return stream_ == nullptr ? 0 : AAudioStream_getXRunCount(stream_);
  }

  int64_t underrunFrames(int deck_index) const {
    const DeckRing* deck = deckAt(deck_index);
    return deck == nullptr
        ? 0
        : deck->underrun_frames.load(std::memory_order_relaxed);
  }

  int64_t droppedFrames(int deck_index) const {
    const DeckRing* deck = deckAt(deck_index);
    if (deck == nullptr) return 0;
    return deck->dropped_samples.load(std::memory_order_relaxed) /
           std::max(channel_count_, 1);
  }

  void close() {
    for (auto& deck : decks_) {
      deck.playing.store(false, std::memory_order_release);
    }
    if (stream_ != nullptr) {
      AAudioStream_requestStop(stream_);
      AAudioStream_close(stream_);
      stream_ = nullptr;
    }
    for (auto& deck : decks_) {
      deck.samples.clear();
      deck.capacity = 0;
      deck.read_index.store(0, std::memory_order_relaxed);
      deck.write_index.store(0, std::memory_order_relaxed);
    }
  }

 private:
  bool openWithSharingMode(aaudio_sharing_mode_t sharing_mode) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK ||
        builder == nullptr) {
      return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, sample_rate_);
    AAudioStreamBuilder_setChannelCount(builder, channel_count_);
    AAudioStreamBuilder_setPerformanceMode(
        builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, sharing_mode);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(
        builder, &AAudioMixer::dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(
        builder, &AAudioMixer::errorCallback, this);

    AAudioStream* opened = nullptr;
    const aaudio_result_t result =
        AAudioStreamBuilder_openStream(builder, &opened);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || opened == nullptr) {
      return false;
    }
    stream_ = opened;
    return true;
  }

  bool anyDeckPlaying() const {
    for (const auto& deck : decks_) {
      if (deck.playing.load(std::memory_order_acquire)) return true;
    }
    return false;
  }

  DeckRing* deckAt(int deck_index) {
    if (deck_index < 0 || deck_index >= kDeckCount) return nullptr;
    return &decks_[static_cast<size_t>(deck_index)];
  }

  const DeckRing* deckAt(int deck_index) const {
    if (deck_index < 0 || deck_index >= kDeckCount) return nullptr;
    return &decks_[static_cast<size_t>(deck_index)];
  }

  static aaudio_data_callback_result_t dataCallback(
      AAudioStream*,
      void* user_data,
      void* audio_data,
      int32_t num_frames) {
    return static_cast<AAudioMixer*>(user_data)->render(
        static_cast<float*>(audio_data), num_frames);
  }

  static void errorCallback(AAudioStream*, void* user_data, aaudio_result_t) {
    auto* mixer = static_cast<AAudioMixer*>(user_data);
    for (auto& deck : mixer->decks_) {
      deck.playing.store(false, std::memory_order_release);
    }
  }

  aaudio_data_callback_result_t render(float* output, int32_t num_frames) {
    if (output == nullptr || num_frames <= 0) {
      return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    const int32_t requested_samples = num_frames * channel_count_;
    std::fill(output, output + requested_samples, 0.0f);

    for (auto& deck : decks_) {
      if (!deck.playing.load(std::memory_order_acquire) ||
          deck.capacity == 0) {
        continue;
      }

      const uint64_t read =
          deck.read_index.load(std::memory_order_relaxed);
      const uint64_t write =
          deck.write_index.load(std::memory_order_acquire);
      const uint64_t available = write - read;
      const int32_t copied = static_cast<int32_t>(
          std::min<uint64_t>(
              available, static_cast<uint64_t>(requested_samples)));
      const float gain = deck.gain.load(std::memory_order_relaxed);

      for (int32_t i = 0; i < copied; ++i) {
        output[i] +=
            deck.samples[static_cast<size_t>((read + i) % deck.capacity)] *
            gain;
      }
      if (copied < requested_samples) {
        deck.underrun_frames.fetch_add(
            (requested_samples - copied) / std::max(channel_count_, 1),
            std::memory_order_relaxed);
      }
      deck.read_index.store(read + copied, std::memory_order_release);
    }

    for (int32_t i = 0; i < requested_samples; ++i) {
      output[i] = std::clamp(output[i], -1.0f, 1.0f);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }

  int32_t sample_rate_;
  int32_t channel_count_;
  AAudioStream* stream_ = nullptr;
  std::array<DeckRing, kDeckCount> decks_;
  std::atomic<int64_t> frames_per_burst_{0};
};

AAudioMixer* fromHandle(jlong handle) {
  return reinterpret_cast<AAudioMixer*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_androidjtools_live_NativeAaudioBridge_nativeCreateMixer(
    JNIEnv*, jobject, jint sample_rate, jint channel_count) {
  auto mixer = std::make_unique<AAudioMixer>(sample_rate, channel_count);
  if (!mixer->open()) return 0;
  return static_cast<jlong>(reinterpret_cast<intptr_t>(mixer.release()));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_androidjtools_live_NativeAaudioBridge_nativeSetDeckPlaying(
    JNIEnv*, jobject, jlong handle, jint deck, jboolean playing) {
  auto* mixer = fromHandle(handle);
  return mixer != nullptr &&
                 mixer->setDeckPlaying(deck, playing == JNI_TRUE)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androidjtools_live_NativeAaudioBridge_nativeSetDeckGain(
    JNIEnv*, jobject, jlong handle, jint deck, jfloat gain) {
  if (auto* mixer = fromHandle(handle)) mixer->setDeckGain(deck, gain);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androidjtools_live_NativeAaudioBridge_nativeFlushDeck(
    JNIEnv*, jobject, jlong handle, jint deck) {
  if (auto* mixer = fromHandle(handle)) mixer->flushDeck(deck);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_androidjtools_live_NativeAaudioBridge_nativeWriteDeckFloats(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint deck,
    jobject buffer,
    jint sample_count) {
  auto* mixer = fromHandle(handle);
  auto* samples = static_cast<float*>(env->GetDirectBufferAddress(buffer));
  if (mixer == nullptr || samples == nullptr) return 0;
  return mixer->writeDeck(deck, samples, sample_count);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_androidjtools_live_NativeAaudioBridge_nativeStats(
    JNIEnv* env, jobject, jlong handle) {
  jlong values[7] = {0, 0, 0, 0, 0, 0, 0};
  if (auto* mixer = fromHandle(handle)) {
    values[0] = mixer->framesPerBurst();
    values[1] = mixer->bufferSizeFrames();
    values[2] = mixer->xRunCount();
    values[3] = mixer->underrunFrames(0);
    values[4] = mixer->underrunFrames(1);
    values[5] = mixer->droppedFrames(0);
    values[6] = mixer->droppedFrames(1);
  }
  jlongArray result = env->NewLongArray(7);
  env->SetLongArrayRegion(result, 0, 7, values);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androidjtools_live_NativeAaudioBridge_nativeClose(
    JNIEnv*, jobject, jlong handle) {
  delete fromHandle(handle);
}
