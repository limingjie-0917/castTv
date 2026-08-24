package com.bd.casttv.airplay;

/** A single H.264 NAL unit received from the AirPlay mirroring stream. */
public class NALPacket {
    public byte[] nalData = null;
    public int nalType = 0;
    public long pts = 0;
}
