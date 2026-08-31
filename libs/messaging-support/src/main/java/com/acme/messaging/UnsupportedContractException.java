package com.acme.messaging;

/**
 * Thrown when a declared contract cannot be honoured by the configured broker.
 *
 * <p>Deliberately unchecked and deliberately fatal at startup. The alternative - provisioning
 * something close enough and logging a warning - produces a system that runs, passes its smoke
 * tests, and loses data on a timescale nobody is watching. A boot failure costs one deployment;
 * the silent version costs a rebuild of state from a source that no longer has it.
 *
 * <p>Messages thrown from here name the event class, the combination that cannot hold, and the
 * ways out. A reader of this exception has to be able to fix it without reading this module.
 */
public class UnsupportedContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message an explanation naming the event class, the impossible combination, and the
     *     available resolutions
     */
    public UnsupportedContractException(String message) {
        super(message);
    }
}
