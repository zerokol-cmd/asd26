using NAudio.Wave;
using System;

namespace asd26.Encoding.Encoders
{
    internal class PCMEncoder : IAudioEncoder
    {
        public AudioCodec Codec => AudioCodec.RawPCM;
        public AudioStreamMetadata StreamInfo { get; }
        public byte[] Header { get; }

        public PCMEncoder(WaveFormat format)
        {
            StreamInfo = new AudioStreamMetadata(AudioCodec.RawPCM, format.SampleRate, format.Channels, format.BitsPerSample, 0);
            using var ms = new MemoryStream();
            using (var bw = new BinaryWriter(ms))
            {
                bw.Write(format.SampleRate);
                bw.Write(format.Channels);
                bw.Write(format.BitsPerSample);
                int audioFormat = (format.Encoding == WaveFormatEncoding.IeeeFloat) ? 3 : 1;
                bw.Write(audioFormat);
            }
            Header = ms.ToArray();
        }
        public void Dispose()
        {
        }

        public byte[] Encode(byte[] pcmData, int offset, int count)
        {
            var result = new byte[count];
            System.Buffer.BlockCopy(pcmData, offset, result, 0, count);
            return result;
        }
    }
}
