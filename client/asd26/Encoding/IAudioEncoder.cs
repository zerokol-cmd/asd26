using System;
using System.Collections.Generic;
using System.Text;

namespace asd26.Encoding
{
    public record EncoderSettings(AudioCodec Codec, int Bitrate = 64_000, int FrameSize = 10, int Complexity = 10);
    public interface IAudioEncoder : IDisposable
    {
        AudioCodec Codec { get; }
        byte[] Header { get; }
        byte[] Encode(byte[] pcmData, int offset, int count);
    }
}
