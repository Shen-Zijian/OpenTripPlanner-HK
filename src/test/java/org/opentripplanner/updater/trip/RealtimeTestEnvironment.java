package org.opentripplanner.updater.trip;

import static org.opentripplanner.updater.trip.UpdateIncrementality.DIFFERENTIAL;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import com.google.transit.realtime.GtfsRealtime;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.opentripplanner.DateTimeHelper;
import org.opentripplanner.ext.siri.SiriFuzzyTripMatcher;
import org.opentripplanner.ext.siri.SiriTimetableSnapshotSource;
import org.opentripplanner.ext.siri.updater.EstimatedTimetableHandler;
import org.opentripplanner.framework.i18n.I18NString;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.model.TimetableSnapshot;
import org.opentripplanner.model.calendar.CalendarServiceData;
import org.opentripplanner.transit.model._data.TransitModelForTest;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripOnServiceDate;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.transit.model.timetable.TripTimesFactory;
import org.opentripplanner.transit.model.timetable.TripTimesStringBuilder;
import org.opentripplanner.transit.service.DefaultTransitService;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.updater.TimetableSnapshotSourceParameters;
import org.opentripplanner.updater.spi.UpdateResult;
import uk.org.siri.siri20.EstimatedTimetableDeliveryStructure;

/**
 * This class exists so that you can share the data building logic for GTFS and Siri tests.
 * Since it's not possible to add a Siri and GTFS updater to the transit model at the same time,
 * they each have their own test environment.
 * <p>
 * It is however a goal to change that and then these two can be combined.
 */
public final class RealtimeTestEnvironment implements RealtimeTestConstants {

  // static constants
  private static final TimetableSnapshotSourceParameters PARAMETERS = new TimetableSnapshotSourceParameters(
    Duration.ZERO,
    false
  );

  public final TransitModel transitModel;
  private final SiriTimetableSnapshotSource siriSource;
  private final TimetableSnapshotSource gtfsSource;
  private final DateTimeHelper dateTimeHelper;

  private Trip trip1;
  private Trip trip2;

  enum SourceType {
    GTFS_RT,
    SIRI,
  }

  /**
   * Siri and GTFS-RT cannot be run at the same time, so you need to decide.
   */
  public static RealtimeTestEnvironmentBuilder siri() {
    return new RealtimeTestEnvironmentBuilder().withSourceType(SourceType.SIRI);
  }

  /**
   * Siri and GTFS-RT cannot be run at the same time, so you need to decide.
   */
  public static RealtimeTestEnvironmentBuilder gtfs() {
    return new RealtimeTestEnvironmentBuilder().withSourceType(SourceType.GTFS_RT);
  }

  RealtimeTestEnvironment(SourceType sourceType, boolean withTrip1, boolean withTrip2) {
    transitModel = new TransitModel(STOP_MODEL, new Deduplicator());
    transitModel.initTimeZone(TIME_ZONE);
    transitModel.addAgency(TransitModelForTest.AGENCY);

    CalendarServiceData calendarServiceData = new CalendarServiceData();
    calendarServiceData.putServiceDatesForServiceId(
      SERVICE_ID,
      List.of(SERVICE_DATE.minusDays(1), SERVICE_DATE, SERVICE_DATE.plusDays(1))
    );
    transitModel.getServiceCodes().put(SERVICE_ID, 0);
    transitModel.updateCalendarServiceData(true, calendarServiceData, DataImportIssueStore.NOOP);

    if (withTrip1) {
      withTrip1();
    }
    if (withTrip2) {
      withTrip2();
    }

    transitModel.index();
    // SIRI and GTFS-RT cannot be registered with the transit model at the same time
    // we are actively refactoring to remove this restriction
    // for the time being you cannot run a SIRI and GTFS-RT test at the same time
    if (sourceType == SourceType.SIRI) {
      siriSource = new SiriTimetableSnapshotSource(PARAMETERS, transitModel);
      gtfsSource = null;
    } else {
      gtfsSource = new TimetableSnapshotSource(PARAMETERS, transitModel);
      siriSource = null;
    }
    dateTimeHelper = new DateTimeHelper(TIME_ZONE, SERVICE_DATE);
  }

  private RealtimeTestEnvironment withTrip1() {
    trip1 =
      createTrip(
        TRIP_1_ID,
        ROUTE_1,
        List.of(new StopCall(STOP_A1, 10, 11), new StopCall(STOP_B1, 20, 21))
      );
    transitModel.index();
    return this;
  }

  private RealtimeTestEnvironment withTrip2() {
    trip2 =
      createTrip(
        TRIP_2_ID,
        ROUTE_1,
        List.of(
          new StopCall(STOP_A1, 60, 61),
          new StopCall(STOP_B1, 70, 71),
          new StopCall(STOP_C1, 80, 81)
        )
      );

    transitModel.index();
    return this;
  }

  public Trip trip1() {
    Objects.requireNonNull(
      trip1,
      "trip1 was not added to the test environment. Call withTrip1() to add it."
    );
    return trip1;
  }

  public Trip trip2() {
    Objects.requireNonNull(
      trip2,
      "trip2 was not added to the test environment. Call withTrip2() to add it."
    );
    return trip2;
  }

  public static FeedScopedId id(String id) {
    return TransitModelForTest.id(id);
  }

  /**
   * Returns a new fresh TransitService
   */
  public TransitService getTransitService() {
    return new DefaultTransitService(transitModel);
  }

  /**
   * Find the current TripTimes for a trip id on a serviceDate
   */
  public TripTimes getTripTimesForTrip(FeedScopedId tripId, LocalDate serviceDate) {
    var transitService = getTransitService();
    var trip = transitService.getTripOnServiceDateById(tripId).getTrip();
    var pattern = transitService.getPatternForTrip(trip, serviceDate);
    var timetable = transitService.getTimetableForTripPattern(pattern, serviceDate);
    return timetable.getTripTimes(trip);
  }

  public String getFeedId() {
    return TransitModelForTest.FEED_ID;
  }

  private EstimatedTimetableHandler getEstimatedTimetableHandler(boolean fuzzyMatching) {
    return new EstimatedTimetableHandler(
      siriSource,
      fuzzyMatching ? new SiriFuzzyTripMatcher(getTransitService()) : null,
      getTransitService(),
      getFeedId()
    );
  }

  public TripPattern getPatternForTrip(FeedScopedId tripId) {
    return getPatternForTrip(tripId, SERVICE_DATE);
  }

  public TripPattern getPatternForTrip(FeedScopedId tripId, LocalDate serviceDate) {
    var transitService = getTransitService();
    var trip = transitService.getTripOnServiceDateById(tripId);
    return transitService.getPatternForTrip(trip.getTrip(), serviceDate);
  }

  /**
   * Find the current TripTimes for a trip id on the default serviceDate
   */
  public TripTimes getTripTimesForTrip(Trip trip) {
    return getTripTimesForTrip(trip.getId(), SERVICE_DATE);
  }

  /**
   * Find the current TripTimes for a trip id on the default serviceDate
   */
  public TripTimes getTripTimesForTrip(String id) {
    return getTripTimesForTrip(id(id), SERVICE_DATE);
  }

  public DateTimeHelper getDateTimeHelper() {
    return dateTimeHelper;
  }

  public TripPattern getPatternForTrip(Trip trip) {
    return getTransitService().getPatternForTrip(trip);
  }

  public TimetableSnapshot getTimetableSnapshot() {
    if (siriSource != null) {
      return siriSource.getTimetableSnapshot();
    } else {
      return gtfsSource.getTimetableSnapshot();
    }
  }

  public String getRealtimeTimetable(String tripId) {
    return getRealtimeTimetable(id(tripId), SERVICE_DATE);
  }

  public String getRealtimeTimetable(Trip trip) {
    return getRealtimeTimetable(trip.getId(), SERVICE_DATE);
  }

  public String getRealtimeTimetable(FeedScopedId tripId, LocalDate serviceDate) {
    var tt = getTripTimesForTrip(tripId, serviceDate);
    var pattern = getPatternForTrip(tripId);

    return TripTimesStringBuilder.encodeTripTimes(tt, pattern);
  }

  public String getScheduledTimetable(String tripId) {
    return getScheduledTimetable(id(tripId));
  }

  public String getScheduledTimetable(FeedScopedId tripId) {
    var pattern = getPatternForTrip(tripId);
    var tt = pattern.getScheduledTimetable().getTripTimes(tripId);

    return TripTimesStringBuilder.encodeTripTimes(tt, pattern);
  }

  // SIRI updates

  public UpdateResult applyEstimatedTimetableWithFuzzyMatcher(
    List<EstimatedTimetableDeliveryStructure> updates
  ) {
    return applyEstimatedTimetable(updates, true);
  }

  public UpdateResult applyEstimatedTimetable(List<EstimatedTimetableDeliveryStructure> updates) {
    return applyEstimatedTimetable(updates, false);
  }

  // GTFS-RT updates

  public UpdateResult applyTripUpdate(GtfsRealtime.TripUpdate update) {
    return applyTripUpdates(List.of(update), FULL_DATASET);
  }

  public UpdateResult applyTripUpdate(
    GtfsRealtime.TripUpdate update,
    UpdateIncrementality incrementality
  ) {
    return applyTripUpdates(List.of(update), incrementality);
  }

  public UpdateResult applyTripUpdates(
    List<GtfsRealtime.TripUpdate> updates,
    UpdateIncrementality incrementality
  ) {
    Objects.requireNonNull(gtfsSource, "Test environment is configured for SIRI only");
    UpdateResult updateResult = gtfsSource.applyTripUpdates(
      null,
      BackwardsDelayPropagationType.REQUIRED_NO_DATA,
      incrementality,
      updates,
      getFeedId()
    );
    commitTimetableSnapshot();
    return updateResult;
  }

  // private methods

  private UpdateResult applyEstimatedTimetable(
    List<EstimatedTimetableDeliveryStructure> updates,
    boolean fuzzyMatching
  ) {
    Objects.requireNonNull(siriSource, "Test environment is configured for GTFS-RT only");
    UpdateResult updateResult = getEstimatedTimetableHandler(fuzzyMatching)
      .applyUpdate(updates, DIFFERENTIAL);
    commitTimetableSnapshot();
    return updateResult;
  }

  private void commitTimetableSnapshot() {
    if (siriSource != null) {
      siriSource.flushBuffer();
    }
    if (gtfsSource != null) {
      gtfsSource.flushBuffer();
    }
  }

  private Trip createTrip(String id, Route route, List<StopCall> stops) {
    var trip = Trip
      .of(id(id))
      .withRoute(route)
      .withHeadsign(I18NString.of("Headsign of %s".formatted(id)))
      .withServiceId(SERVICE_ID)
      .build();

    var tripOnServiceDate = TripOnServiceDate
      .of(trip.getId())
      .withTrip(trip)
      .withServiceDate(SERVICE_DATE)
      .build();

    transitModel.addTripOnServiceDate(tripOnServiceDate.getId(), tripOnServiceDate);

    var stopTimes = IntStream
      .range(0, stops.size())
      .mapToObj(i -> {
        var stop = stops.get(i);
        return createStopTime(trip, i, stop.stop(), stop.arrivalTime(), stop.departureTime());
      })
      .toList();

    TripTimes tripTimes = TripTimesFactory.tripTimes(trip, stopTimes, null);

    final TripPattern pattern = TransitModelForTest
      .tripPattern(id + "Pattern", route)
      .withStopPattern(TransitModelForTest.stopPattern(stops.stream().map(StopCall::stop).toList()))
      .build();
    pattern.add(tripTimes);

    transitModel.addTripPattern(pattern.getId(), pattern);

    return trip;
  }

  private StopTime createStopTime(
    Trip trip,
    int stopSequence,
    StopLocation stop,
    int arrivalTime,
    int departureTime
  ) {
    var st = new StopTime();
    st.setTrip(trip);
    st.setStopSequence(stopSequence);
    st.setStop(stop);
    st.setArrivalTime(arrivalTime);
    st.setDepartureTime(departureTime);
    return st;
  }

  private record StopCall(RegularStop stop, int arrivalTime, int departureTime) {}
}
