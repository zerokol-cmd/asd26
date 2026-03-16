using System;
using System.Collections.Generic;
using System.Text;

namespace asd26.Encoding
{
    public enum AudioCodec
    {
        RawPCM= 1, 
        Opus = 2, 
        Vorbis = 3, 
    }
    public record AudioStreamMetadata(AudioCodec Codec, int SampleRate, int Channels, int BitsPerSample, int Bitrate);



}
