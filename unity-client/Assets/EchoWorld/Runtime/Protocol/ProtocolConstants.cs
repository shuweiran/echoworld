namespace EchoWorld.Client.Protocol
{
    public static class ProtocolConstants
    {
        public const int CurrentVersion = 1;

        public const string FullSnapshot = "full_snapshot";
        public const string ReplicationFrame = "replication_frame";
        public const string AckResult = "ack_result";
        public const string Error = "error";

        public const string Hello = "hello";
        public const string Interest = "interest";
        public const string Ack = "ack";
        public const string Replay = "replay";
    }
}
