package hust.can;
interface ICan
{
    boolean canOpen(String ifName, int bitrate);
    void canClose();
    boolean canWrite(int canId, in byte[] data, boolean extended);
    int canRead(out byte[] data);
}