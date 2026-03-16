using NAudio.Wave;
using System;
using System.Collections.Generic;
using System.Text;

namespace asd26.Encoding
{
    internal interface IEncoderFactory
    {
        IAudioEncoder Create(WaveFormat inputFormat, EncoderSettings settings);
    }
}
