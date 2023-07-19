package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.framework.model.Cost;
import org.opentripplanner.framework.model.TimeAndCost;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.DefaultAccessEgress;
import org.opentripplanner.street.search.state.TestStateBuilder;

class AccessEgressesTest {

  public static final Duration D3m = Duration.ofMinutes(3);
  public static final Duration D7m = Duration.ofMinutes(7);
  private static final DefaultAccessEgress ACCESS_A = new DefaultAccessEgress(
    1,
    TestStateBuilder.ofWalking().build()
  )
    .withPenalty(new TimeAndCost(D3m, Cost.ZERO));
  private static final DefaultAccessEgress ACCESS_B = new DefaultAccessEgress(
    1,
    TestStateBuilder.ofWalking().build()
  )
    .withPenalty(new TimeAndCost(D7m, Cost.ZERO));
  private static final DefaultAccessEgress ACCESS_C = new DefaultAccessEgress(
    1,
    TestStateBuilder.ofWalking().build()
  );
  private static final List<DefaultAccessEgress> ACCESSES = List.of(ACCESS_A, ACCESS_B, ACCESS_C);
  private static final DefaultAccessEgress EGRESS_A = new DefaultAccessEgress(
    1,
    TestStateBuilder.ofWalking().build()
  );
  private static final DefaultAccessEgress EGRESS_B = new DefaultAccessEgress(
    1,
    TestStateBuilder.ofWalking().build()
  );
  private static final List<DefaultAccessEgress> EGRESSES = List.of(EGRESS_A, EGRESS_B);

  private final AccessEgresses subject = new AccessEgresses(ACCESSES, EGRESSES);

  @Test
  void getAccesses() {
    assertEquals(ACCESSES, subject.getAccesses());
  }

  @Test
  void getEgresses() {
    assertEquals(EGRESSES, subject.getEgresses());
  }

  @Test
  void calculateMaxAccessTimePenalty() {
    assertEquals(D7m, subject.calculateMaxAccessTimePenalty());
  }
}
