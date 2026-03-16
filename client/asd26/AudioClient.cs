using asd26.Encoding;
using asd26.Encoding.Encoders;
using NAudio.Wave;
using System;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;

namespace asd26
{
    internal class AudioClient
    {
        private UdpClient _udpClient = new();
        private WindowsAudioCapture _audioCapture = new();
        private readonly IAudioEncoder _encoder;

        private readonly Channel<byte[]> _sendChannel = Channel.CreateUnbounded<byte[]>(
            new UnboundedChannelOptions { SingleReader = true, SingleWriter = false }
        );
        private readonly Channel<byte[]> _wavChannel = Channel.CreateUnbounded<byte[]>(
            new UnboundedChannelOptions { SingleReader = true, SingleWriter = false }
        );

        public AudioClient()
        {
            var factory = new AudioEncoderFactory();
            _encoder = factory.Create(_audioCapture.Format,
                new EncoderSettings(AudioCodec.Opus, Bitrate: 128_000));
        }

        public void Connect(string ip, int port)
        {
            _udpClient.Connect(ip, port);
            Console.WriteLine($"UDP target set: {ip}:{port}");
        }

        public async Task Start()
        {
            await _udpClient.SendAsync(_encoder.Header, _encoder.Header.Length);
            Console.WriteLine($"Sent {_encoder.Codec} header ({_encoder.Header.Length} bytes)");

            // Dedicated sender — one SendAsync in flight at a time
            _ = Task.Run(async () =>
            {
                await foreach (var packet in _sendChannel.Reader.ReadAllAsync())
                    await _udpClient.SendAsync(packet, packet.Length);
            });


            _audioCapture.DataReady += (_, chunk) =>
            {
                if (_encoder is OpusEncoder opusEncoder)
                {
                    var packets = opusEncoder.EncodePackets(chunk, 0, chunk.Length);
                    foreach (var packet in packets)
                        _sendChannel.Writer.TryWrite(packet);
                }

            };

            _audioCapture.Start();
            Console.WriteLine($"Streaming {_encoder.Codec} audio over UDP...");
            await Task.Delay(-1);
        }

        public void Dispose()
        {
            _sendChannel.Writer.Complete();
            _wavChannel.Writer.Complete();
            _audioCapture.Dispose();
            _encoder.Dispose();
            _udpClient.Dispose();
        }
    }
}