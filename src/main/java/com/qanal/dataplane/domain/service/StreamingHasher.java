package com.qanal.dataplane.domain.service;

import java.nio.ByteBuffer;

/**
 * Port interface for streaming hash computation.
 * Implementations must be stateful and not thread-safe (one per stream).
 */
public interface StreamingHasher {

    void update(byte[] data, int off, int len);

    void update(ByteBuffer buf);

    /** Returns the accumulated hash as a lowercase hex string. */
    String hexDigest();

    /** Resets state for reuse. */
    void reset();
}
