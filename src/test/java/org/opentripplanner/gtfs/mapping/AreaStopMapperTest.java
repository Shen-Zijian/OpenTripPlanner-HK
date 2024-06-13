package org.opentripplanner.gtfs.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Polygon;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Location;
import org.opentripplanner._support.geometry.Polygons;
import org.opentripplanner.transit.service.StopModel;

class AreaStopMapperTest {

  static Stream<Arguments> testCases() {
    return Stream.of(Arguments.of(null, true), Arguments.of("a name", false));
  }

  @ParameterizedTest(name = "a name of <{0}> should set bogusName={1}")
  @MethodSource("testCases")
  void testMapping(String name, boolean isBogusName) {
    final var gtfsLocation = getLocation(name, Polygons.OSLO);

    var mapper = new AreaStopMapper(StopModel.of());
    var flexLocation = mapper.map(gtfsLocation);

    assertEquals(isBogusName, flexLocation.hasFallbackName());
  }

  @Test
  void invalidPolygon() {
    var selfIntersecting = Polygons.SELF_INTERSECTING;
    assertFalse(selfIntersecting.isValid());

    var gtfsLocation = getLocation("invalid", selfIntersecting);

    var mapper = new AreaStopMapper(StopModel.of());
    var expectation = assertThrows(IllegalArgumentException.class, () -> mapper.map(gtfsLocation));
    assertEquals(
      "Polygon geometry for AreaStop 1:zone-3 is invalid: Self-intersection at (lat: 1.0, lon: 2.0)",
      expectation.getMessage()
    );
  }

  private static Location getLocation(String name, Polygon polygon) {
    var gtfsLocation = new Location();
    gtfsLocation.setId(new AgencyAndId("1", "zone-3"));
    gtfsLocation.setName(name);
    gtfsLocation.setGeometry(Polygons.toGeoJson(polygon));
    return gtfsLocation;
  }
}
