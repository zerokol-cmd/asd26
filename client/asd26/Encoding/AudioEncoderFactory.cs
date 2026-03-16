using asd26.Encoding.Encoders;
using NAudio.Wave;
using System;
using System.Collections.Generic;
using System.Text;

namespace asd26.Encoding
{
    internal class AudioEncoderFactory : IEncoderFactory
    {
        public IAudioEncoder Create(WaveFormat inputFormat, EncoderSettings settings) => settings.Codec switch
        {
            AudioCodec.RawPCM => new PCMEncoder(inputFormat),
            AudioCodec.Opus => new OpusEncoder(inputFormat, settings),
            _ => throw new NotSupportedException($"Codec {settings.Codec} has no registered encoder")
        };
    }
}
