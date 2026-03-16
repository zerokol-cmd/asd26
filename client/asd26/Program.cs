namespace asd26
{
    internal class Program
    {
        static async Task Main(string[] args)
        {
            AudioClient audioClient = new AudioClient();

            audioClient.Connect(args[0], int.Parse(args[1]));
            //await audioClient.Connect("192.168.1.103", 33201);
            await audioClient.Start();
        }
    }
}
