using System;
using System.Collections.Generic;
using System.IO;
using Concentus.Enums;
using Concentus.Structs;
using NAudio.Wave;

namespace asd26.Encoding.Encoders
{
    internal class OpusEncoder : IAudioEncoder
    {
        public AudioCodec Codec => AudioCodec.Opus;
        public AudioStreamMetadata StreamInfo { get; }
        public byte[] Header { get; }

        private readonly Concentus.Structs.OpusEncoder _encoder;
        private readonly int _frameSize;
        private readonly int _channels;
        private readonly bool _inputIsFloat;
        private readonly short[] _accumulator;
        private int _accumulatorOffset;
        private readonly int _frameSizeSamples;
        private readonly object _lock = new();

        public OpusEncoder(WaveFormat format, EncoderSettings settings)
        {
            _channels = format.Channels;
            _inputIsFloat = format.Encoding == WaveFormatEncoding.IeeeFloat;
            int sampleRate = format.SampleRate;
            _frameSize = sampleRate * settings.FrameSize / 1000;
            _frameSizeSamples = _frameSize * _channels;
            _accumulator = new short[_frameSizeSamples];
            _accumulatorOffset = 0;

            _encoder = new Concentus.Structs.OpusEncoder(sampleRate, _channels, OpusApplication.OPUS_APPLICATION_AUDIO);
            _encoder.Bitrate = settings.Bitrate;
            StreamInfo = new AudioStreamMetadata(AudioCodec.Opus, sampleRate, _channels, 16, settings.Bitrate);

            using var ms = new MemoryStream();
            using (var bw = new BinaryWriter(ms))
            {
                bw.Write(new byte[] { (byte)'O', (byte)'P', (byte)'U', (byte)'S' });
                bw.Write(sampleRate);
                bw.Write((byte)_channels);
                bw.Write((ushort)_frameSize);
            }
            Header = ms.ToArray();
        }

        // Returns zero or more complete Opus packets — one per full accumulated frame
        public List<byte[]> EncodePackets(byte[] pcmData, int offset, int count)
        {
            var packets = new List<byte[]>();
            short[] incoming = ConvertToShorts(pcmData, offset, count);
            byte[] opusBuf = new byte[4000];
            int inputOffset = 0;

            lock (_lock)
            {
                while (inputOffset < incoming.Length)
                {
                    int spaceInFrame = _frameSizeSamples - _accumulatorOffset;
                    int available = incoming.Length - inputOffset;
                    int toCopy = Math.Min(spaceInFrame, available);

                    Array.Copy(incoming, inputOffset, _accumulator, _accumulatorOffset, toCopy);
                    _accumulatorOffset += toCopy;
                    inputOffset += toCopy;

                    if (_accumulatorOffset == _frameSizeSamples)
                    {
                        int encoded = _encoder.Encode(_accumulator, 0, _frameSize, opusBuf, 0, opusBuf.Length);
                        if (encoded > 0)
                        {
                            byte[] packet = new byte[encoded];
                            Buffer.BlockCopy(opusBuf, 0, packet, 0, encoded);
                            packets.Add(packet);
                        }
                        _accumulatorOffset = 0;
                    }
                }
            }

            return packets;
        }

        // IAudioEncoder compliance — not used for Opus over UDP
        public byte[] Encode(byte[] pcmData, int offset, int count)
        {
            EncodePackets(pcmData, offset, count);
            return Array.Empty<byte>();
        }

        private short[] ConvertToShorts(byte[] pcmData, int offset, int count)
        {
            if (_inputIsFloat)
            {
                int sampleCount = count / 4;
                short[] result = new short[sampleCount];
                for (int i = 0; i < sampleCount; i++)
                {
                    float f = BitConverter.ToSingle(pcmData, offset + i * 4);
                    f = Math.Max(-1f, Math.Min(1f, f));
                    result[i] = (short)(f * short.MaxValue);
                }
                return result;
            }
            else
            {
                int sampleCount = count / 2;
                short[] result = new short[sampleCount];
                Buffer.BlockCopy(pcmData, offset, result, 0, count);
                return result;
            }
        }

        public void Dispose() { }
    }
}