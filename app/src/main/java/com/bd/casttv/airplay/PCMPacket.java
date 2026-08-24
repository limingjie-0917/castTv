package com.bd.casttv.airplay;

/** A decoded PCM audio frame from the AirPlay mirroring stream. */
public class PCMPacket {
    public byte[] data;
    public long pts;
}
