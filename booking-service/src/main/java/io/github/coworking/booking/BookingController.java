package io.github.coworking.booking;

import io.github.coworking.booking.workspace.WorkspaceClient;
import io.github.coworking.jwt.AuthHeaders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

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
        return bookings.findByUserIdOrderByStartsAt(userId).stream().map(BookingResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@RequestHeader(AuthHeaders.USER_ID) long userId,
                                  @Valid @RequestBody CreateBookingRequest request) {
        requireWorkspace(request.workspaceId());
        Booking booking = new Booking(userId, request.workspaceId(), request.startsAt(), request.endsAt());
        return BookingResponse.from(bookings.save(booking));
    }

    /**
     * Workspaces live in another service's database, so there is no foreign key to rely on: the
     * service that owns them is asked instead.
     */
    private void requireWorkspace(long workspaceId) {
        try {
            workspaces.get(workspaceId);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workspace " + workspaceId + " does not exist");
        } catch (RestClientException e) {
            // Down, timed out or answering 5xx. Not booking without the check: an unverified
            // booking for a workspace that does not exist is worse than asking the client to retry.
            log.warn("Cannot verify workspace {}: {}", workspaceId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Workspaces cannot be checked right now; try again later");
        }
    }

    public record CreateBookingRequest(
            @NotNull @Positive Long workspaceId,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt) {

        @AssertTrue(message = "endsAt must be after startsAt")
        boolean isEndsAtAfterStartsAt() {
            return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
        }
    }

    public record BookingResponse(long id, long workspaceId, Instant startsAt, Instant endsAt) {

        static BookingResponse from(Booking booking) {
            return new BookingResponse(
                    booking.getId(), booking.getWorkspaceId(), booking.getStartsAt(), booking.getEndsAt());
        }
    }
}
