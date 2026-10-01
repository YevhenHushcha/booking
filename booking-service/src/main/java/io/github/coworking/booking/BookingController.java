package io.github.coworking.booking;

import io.github.coworking.booking.workspace.WorkspaceClient;
import feign.FeignException;
import io.github.coworking.jwt.AuthHeaders;
import javax.validation.Valid;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    private final BookingRepository bookings;
    private final WorkspaceClient workspaces;

    public BookingController(BookingRepository bookings, WorkspaceClient workspaces) {
        this.bookings = bookings;
        this.workspaces = workspaces;
    }

    /** Only the caller's own bookings: the owner is taken from the gateway header, never from the request. */
    @GetMapping
    public List<BookingResponse> mine(@RequestHeader(AuthHeaders.USER_ID) long userId) {
        return bookings.findByUserIdOrderByStartsAt(userId).stream().map(BookingResponse::from).collect(Collectors.toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@RequestHeader(AuthHeaders.USER_ID) long userId,
                                  @Valid @RequestBody CreateBookingRequest request) {
        requireWorkspace(request.getWorkspaceId());
        Booking booking = new Booking(userId, request.getWorkspaceId(), request.getStartsAt(), request.getEndsAt());
        return BookingResponse.from(bookings.save(booking));
    }

    /**
     * Workspaces live in another service's database, so there is no foreign key to rely on: the
     * service that owns them is asked instead.
     */
    private void requireWorkspace(long workspaceId) {
        try {
            workspaces.get(workspaceId);
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workspace " + workspaceId + " does not exist");
        } catch (FeignException e) {
            // Down, timed out (RetryableException is a FeignException too) or answering 5xx. Not booking without the check: an unverified
            // booking for a workspace that does not exist is worse than asking the client to retry.
            log.warn("Cannot verify workspace {}: {}", workspaceId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Workspaces cannot be checked right now; try again later");
        }
    }

    public static class CreateBookingRequest {

        @NotNull
        @Positive
        private Long workspaceId;

        @NotNull
        private Instant startsAt;

        @NotNull
        private Instant endsAt;

        @AssertTrue(message = "endsAt must be after startsAt")
        boolean isEndsAtAfterStartsAt() {
            return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
        }

        public Long getWorkspaceId() {
            return workspaceId;
        }

        public void setWorkspaceId(Long workspaceId) {
            this.workspaceId = workspaceId;
        }

        public Instant getStartsAt() {
            return startsAt;
        }

        public void setStartsAt(Instant startsAt) {
            this.startsAt = startsAt;
        }

        public Instant getEndsAt() {
            return endsAt;
        }

        public void setEndsAt(Instant endsAt) {
            this.endsAt = endsAt;
        }
    }

    public static class BookingResponse {

        private final long id;
        private final long workspaceId;
        private final Instant startsAt;
        private final Instant endsAt;

        BookingResponse(long id, long workspaceId, Instant startsAt, Instant endsAt) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
        }

        static BookingResponse from(Booking booking) {
            return new BookingResponse(
                    booking.getId(), booking.getWorkspaceId(), booking.getStartsAt(), booking.getEndsAt());
        }

        public long getId() {
            return id;
        }

        public long getWorkspaceId() {
            return workspaceId;
        }

        public Instant getStartsAt() {
            return startsAt;
        }

        public Instant getEndsAt() {
            return endsAt;
        }
    }
}
