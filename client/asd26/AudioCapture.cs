using System;

namespace asd26
{
    public abstract class AudioCapture : IDisposable
    {
        public abstract byte[] GetAudioChunk();
        public abstract void Dispose();
    }
}