package ocs;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Which failures the entry commands show as a sentence (stage 06).
 *
 * <p>Before this, an unwritable save folder reached {@code IJ.handleException}
 * and showed a stack trace, which reads as a crash.
 */
public class OCSErrorsTest {

    @Test
    public void wrongInputIsItsOwnMessage() {
        assertEquals("folder= is needed",
                OCSErrors.messageFor(new IllegalArgumentException("folder= is needed")));
    }

    @Test
    public void aFileThatCannotBeWrittenIsASentenceNotAStackTrace() {
        String message = OCSErrors.messageFor(
                new IOException("could not create Q:\\out\\Object Colocalization Suite"));
        assertNotNull(message);
        assertTrue(message, message.startsWith("Could not read or save a file"));
        assertTrue(message, message.contains("Q:\\out"));
    }

    @Test
    public void aWrappedFileFailureIsFoundThroughItsCause() {
        String message = OCSErrors.messageFor(new IllegalStateException(
                "could not write verdict-agreement.csv", new IOException("disk full")));
        assertNotNull(message);
        assertTrue(message, message.contains("disk full"));
    }

    @Test
    public void runningOutOfMemoryInsideAWorkerIsStillAMemoryMessage() {
        String message = OCSErrors.messageFor(new IllegalStateException(
                "null model permutation failed",
                new java.util.concurrent.ExecutionException(new OutOfMemoryError())));
        assertEquals(OCSErrors.memoryMessage(), message);
    }

    @Test
    public void aGenuineBugKeepsItsStackTrace() {
        assertNull(OCSErrors.messageFor(new NullPointerException()));
        assertNull(OCSErrors.messageFor(new IllegalStateException("engine declared twice")));
    }
}
