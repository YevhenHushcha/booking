package io.github.coworking.booking;

import io.github.coworking.jwt.AuthHeaders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingRepository bookings;

    public BookingController(BookingRepository bookings) {
        this.bookings = bookings;
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
        Booking booking = new Booking(userId, request.workspaceId(), request.startsAt(), request.endsAt());
        return BookingResponse.from(bookings.save(booking));
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
