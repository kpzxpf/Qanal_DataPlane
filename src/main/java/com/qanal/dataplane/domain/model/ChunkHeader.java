package com.qanal.dataplane.domain.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Value object representing the 68-byte binary header per QUIC stream.
 *
 * <p>Wire format (big-endian):
 * <pre>
 *  Byte  0-35 : transferId  (UTF-8, 36 bytes, fixed UUID string)
 *  Byte 36-39 : chunkIndex  (int32)
 *  Byte 40-47 : offsetBytes (int64)
 *  Byte 48-55 : sizeBytes   (int64)
 *  Byte 56-63 : totalFileSize (int64)   ← NEW (B4 fix)
 *  Byte 64-67 : totalChunks  (int32)    ← NEW (B4 fix)
 *  Byte 68+   : chunk data
 * </pre>
 */
public record ChunkHeader(
        String transferId,
        int    chunkIndex,
        long   offsetBytes,
        long   sizeBytes,
        long   totalFileSize,
        int    totalChunks
) {

    public static final int HEADER_SIZE = 68;

    /**
     * Parses a 68-byte big-endian header buffer.
     *
     * @param buf byte array of exactly {@value #HEADER_SIZE} bytes
     * @return parsed header
     */
    public static ChunkHeader parse(byte[] buf) {
        if (buf.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Header too short: expected " + HEADER_SIZE + ", got " + buf.length);
        }
        String transferId   = new String(buf, 0, 36);
        int    chunkIndex   = readInt(buf, 36);
        long   offsetBytes  = readLong(buf, 40);
        long   sizeBytes    = readLong(buf, 48);
        long   totalFileSize = readLong(buf, 56);
        int    totalChunks  = readInt(buf, 64);

        return new ChunkHeader(transferId, chunkIndex, offsetBytes, sizeBytes, totalFileSize, totalChunks);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long readLong(byte[] b, int off) {
        return ((long)(b[off]     & 0xFF) << 56) | ((long)(b[off + 1] & 0xFF) << 48)
             | ((long)(b[off + 2] & 0xFF) << 40) | ((long)(b[off + 3] & 0xFF) << 32)
             | ((long)(b[off + 4] & 0xFF) << 24) | ((long)(b[off + 5] & 0xFF) << 16)
             | ((long)(b[off + 6] & 0xFF) <<  8) |  (long)(b[off + 7] & 0xFF);
    }
}
