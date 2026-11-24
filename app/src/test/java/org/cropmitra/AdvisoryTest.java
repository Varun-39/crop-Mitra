package org.cropmitra;

import org.junit.Test;

import java.util.List;

import static org.cropmitra.Advisory.*;
import static org.junit.Assert.*;

public class AdvisoryTest {
    @Test
    public void pestDamageRequestsCloseUpAndSevereLarvaEscalates() {
        Evidence damage = new Evidence(Finding.PEST_DAMAGE, Stage.EARLY, Severity.UNKNOWN, false);
        Evidence larva = new Evidence(Finding.LARVA, Stage.EARLY, Severity.SEVERE, true);
        assertEquals(List.of(Action.WHORL_PHOTO), eligible(damage));
        assertEquals(Action.EXTENSION, eligible(larva).get(0));
        assertNull(parse("EXTENSION", eligible(damage)));
        assertEquals(Action.EXTENSION, parse(" EXTENSION\n", eligible(larva)));
        assertNull(parse("EXTENSION because it is severe", eligible(larva)));
    }
}
