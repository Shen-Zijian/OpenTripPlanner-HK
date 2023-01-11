package org.opentripplanner.ext.siri;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripIdAndServiceDate;
import org.opentripplanner.transit.model.timetable.TripOnServiceDate;
import org.opentripplanner.transit.service.TransitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.siri.siri20.DatedVehicleJourneyRef;
import uk.org.siri.siri20.EstimatedVehicleJourney;
import uk.org.siri.siri20.FramedVehicleJourneyRefStructure;
import uk.org.siri.siri20.MonitoredVehicleJourneyStructure;

/**
 * This class is responsible for resolving references to various entities in the transit model for
 * the SIRI updaters
 */
public class EntityResolver {

  private static final Logger LOG = LoggerFactory.getLogger(EntityResolver.class);

  private final TransitService transitService;

  private final String feedId;

  public EntityResolver(TransitService transitService, String feedId) {
    this.transitService = transitService;
    this.feedId = feedId;
  }

  /**
   * Resolve a {@link Trip} either by resolving a service journey id from EstimatedVehicleJourney ->
   * FramedVehicleJourneyRef -> DatedVehicleJourneyRef or a dated service journey id from
   * EstimatedVehicleJourney -> DatedVehicleJourneyRef.
   */
  public Trip resolveTrip(EstimatedVehicleJourney journey) {
    Trip trip = resolveTrip(journey.getFramedVehicleJourneyRef());
    if (trip != null) {
      return trip;
    }

    if (journey.getDatedVehicleJourneyRef() != null) {
      String datedServiceJourneyId = journey.getDatedVehicleJourneyRef().getValue();
      TripOnServiceDate tripOnServiceDate = transitService.getTripOnServiceDateById(
        new FeedScopedId(feedId, datedServiceJourneyId)
      );

      if (tripOnServiceDate != null) {
        return tripOnServiceDate.getTrip();
      }
    }

    return null;
  }

  /**
   * Resolve a {@link Trip} by resolving a service journey id from MonitoredVehicleJourney ->
   * FramedVehicleJourneyRef -> DatedVehicleJourneyRef.
   */
  public Trip resolveTrip(MonitoredVehicleJourneyStructure journey) {
    return resolveTrip(journey.getFramedVehicleJourneyRef());
  }

  public TripOnServiceDate resolveTripOnServiceDate(
    EstimatedVehicleJourney estimatedVehicleJourney
  ) {
    FeedScopedId datedServiceJourneyId = resolveDatedServiceJourneyId(estimatedVehicleJourney);
    if (datedServiceJourneyId != null) {
      return resolveTripOnServiceDate(datedServiceJourneyId);
    }
    return null;
  }

  public TripOnServiceDate resolveTripOnServiceDate(String datedServiceJourneyId) {
    return resolveTripOnServiceDate(new FeedScopedId(feedId, datedServiceJourneyId));
  }

  public TripOnServiceDate resolveTripOnServiceDate(
    FramedVehicleJourneyRefStructure framedVehicleJourney
  ) {
    LocalDate serviceDate = resolveServiceDate(framedVehicleJourney);

    if (serviceDate == null) {
      return null;
    }

    return transitService.getTripOnServiceDateForTripAndDay(
      new TripIdAndServiceDate(
        new FeedScopedId(feedId, framedVehicleJourney.getDatedVehicleJourneyRef()),
        serviceDate
      )
    );
  }

  public TripOnServiceDate resolveTripOnServiceDate(FeedScopedId datedServiceJourneyId) {
    return transitService.getTripOnServiceDateById(datedServiceJourneyId);
  }

  public FeedScopedId resolveDatedServiceJourneyId(
    EstimatedVehicleJourney estimatedVehicleJourney
  ) {
    DatedVehicleJourneyRef datedVehicleJourneyRef = estimatedVehicleJourney.getDatedVehicleJourneyRef();
    if (datedVehicleJourneyRef != null) {
      return new FeedScopedId(feedId, datedVehicleJourneyRef.getValue());
    }

    if (estimatedVehicleJourney.getEstimatedVehicleJourneyCode() != null) {
      return new FeedScopedId(feedId, estimatedVehicleJourney.getEstimatedVehicleJourneyCode());
    }

    return null;
  }

  public LocalDate resolveServiceDate(FramedVehicleJourneyRefStructure vehicleJourneyRefStructure) {
    if (vehicleJourneyRefStructure.getDataFrameRef() != null) {
      var dataFrame = vehicleJourneyRefStructure.getDataFrameRef();
      if (dataFrame != null) {
        try {
          return LocalDate.parse(dataFrame.getValue());
        } catch (DateTimeParseException ignored) {
          LOG.warn("Invalid dataFrame format: {}", dataFrame.getValue());
        }
      }
    }
    return null;
  }

  /**
   * Resolve a {@link Trip} by resolving a service journey id from FramedVehicleJourneyRef ->
   * DatedVehicleJourneyRef.
   */
  public Trip resolveTrip(FramedVehicleJourneyRefStructure journey) {
    if (journey != null) {
      String serviceJourneyId = journey.getDatedVehicleJourneyRef();
      return transitService.getTripForId(new FeedScopedId(feedId, serviceJourneyId));
    }
    return null;
  }

  /**
   * Resolve a {@link RegularStop} from a quay id.
   */
  public RegularStop resolveQuay(String quayRef) {
    return transitService.getRegularStop(new FeedScopedId(feedId, quayRef));
  }
}
