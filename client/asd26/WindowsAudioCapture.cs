using System;
using NAudio.Wave;

namespace asd26
{
    internal class WindowsAudioCapture : AudioCapture
    {
        private readonly WasapiLoopbackCapture _capture;
        private bool _disposed;
        public WaveFormat Format { get; }
        public event EventHandler<byte[]>? DataReady;

        public WindowsAudioCapture()
        {
            _capture = new WasapiLoopbackCapture();
            Format = _capture.WaveFormat;
            _capture.DataAvailable += OnDataAvailable;
        }

        private void OnDataAvailable(object? sender, WaveInEventArgs e)
        {
            if (e.BytesRecorded == 0) return;
            var chunk = new byte[e.BytesRecorded];
            Buffer.BlockCopy(e.Buffer, 0, chunk, 0, e.BytesRecorded);
            DataReady?.Invoke(this, chunk);
        }

        public void Start() => _capture.StartRecording();

        public override byte[] GetAudioChunk() => Array.Empty<byte>();

        public override void Dispose()
        {
            if (!_disposed)
            {
                _capture.StopRecording();
                _capture.Dispose();
                _disposed = true;
            }
        }
    }
}
