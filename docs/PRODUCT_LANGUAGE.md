# Airlock Product Language

Last updated: August 24, 2026

This document defines the characters, roles, and vocabulary used throughout the app. New UI copy, notifications, SMS text, screenshots, and documentation should follow it.

## Core Concept

Airlock turns a screen-time promise into a playful interaction. The Goose is both the character enforcing the promise and the user's on-screen stand-in when a limited app is blocked. That is intentional: the Goose guards the limit, asks for more time, and is let loose when the request is approved.

The person receiving the request is not the Goose. That person is the Keyholder.

## Roles

| Name | Role | Can do |
| --- | --- | --- |
| Airlock | The product name | Measure selected-app usage, store limits, coordinate blocking and approval |
| the Goose | Mascot, blocker, and stand-in for the user | Guard apps, count time, ask for extra time, be let loose |
| the Keyholder | Trusted person outside the app | Hold the shared Master PIN, receive a request text, decide whether to approve it, and calculate the reply |
| the user | Person using the limited phone | Configure Airlock with the Keyholder, use apps, and enter returned approval codes |

The Keyholder must hold the same exact four-digit PIN (other than `0000`) called
the Master PIN in Airlock. That one PIN authorizes Duty/settings actions in the
app and is the number used outside the app to calculate ordinary approval
replies. Do not invent a separate `Keyholder PIN` in copy or settings.

## Emergency Day Pass

Emergency codes are pre-authorized recovery access for cases where the user genuinely needs the phone and the Keyholder is unavailable. They are separate from ordinary extra-time approval codes and from the master PIN.

- The master PIN is required to generate or replace the set.
- A set contains three random 8-digit codes.
- Replacing the set revokes every unused code from the previous set.
- Each code works once and pauses all Goose blocking for 24 hours.
- Goose duty resumes automatically after the pause.
- The UI must call these `emergency codes` or an `emergency day pass`, never a user-created override PIN.
- Do not imply that the Keyholder is making a live approval decision when one is used; approval happened when the codes were generated and entrusted to the user.

## Required Vocabulary

- Product name: `Airlock`
- Monitoring mode: `Goose duty`
- Trusted approval person: `Keyholder`
- Request action: `Text the Keyholder`
- Stored number: `Keyholder phone number`
- Successful unlock action: `Loose the Goose`
- Successful state: `The goose is loose`

Use `Airlock` only when describing the underlying app or system behavior in technical or permission copy. Use `the goose` when the UI is speaking through the mascot.

## Voice

The Goose can be silly, stern, and direct. Important requirements and errors must remain immediately understandable even when the surrounding copy is playful.

Good:

- `The goose says time's up!`
- `Text the Keyholder`
- `The Keyholder number is required before goose duty can start.`
- `That code did not honk. Request a new one if needed.`
- `The goose is loose for 10 minutes!`

Avoid:

- `Text the Goose` because the Goose is making the request, not receiving it.
- `Goose phone number` because the number belongs to the Keyholder.
- `The Goose approved it` because approval comes from the Keyholder.
- `Airlock approved your request` because Airlock validates the returned code but does not make the human decision.
- Claims such as `cannot be bypassed` or `parental-control secure`; the current overlay is intentional friction, not device-owner enforcement.

## Approval Flow

The blocking screen should tell one coherent story:

1. The goose has reached the daily limit.
2. The goose chooses how many extra minutes to request.
3. Airlock opens a text to the Keyholder with the request code.
4. The Keyholder decides whether to approve the request and sends back an approval code.
5. The user enters that code in Airlock.
6. The goose is let loose for exactly the approved request duration.

The requested-minutes field remains editable after a request and both numeric
fields survive temporary overlay hide/reopen. A new request creates a separate
pending approval; it does not replace or mutate earlier requests. The blocker
must not open the keyboard on appearance. It may focus and open the keyboard
only after explicit input interaction or the explicit text-request action.
Rebuilding the blocker after another app was foregrounded restores root focus
and keeps the keyboard hidden.

The blocker home must state when one or more requests are waiting. When a new
request is started while another is active, say plainly that the earlier reply
still works and grants the minutes in its own request text. Do not call the new
request a replacement or imply that it cancels an earlier one.

Recommended blocker navigation labels:

1. `Ask for extra time` or `Make another request`
2. `Create request & open text`
3. `Enter approval code`
4. `Loose the Goose!`
5. `Emergency day pass`

Recommended SMS structure:

`The Goose is asking for 10 minutes of extra time in YouTube! Request code: 4321. To approve, multiply the request code by the 4-digit Master PIN, drop the product's last 2 digits, then send the last 4 digits left (add leading zeroes).`

The SMS may identify the requested app, duration, request code, and PIN-based
calculation instructions. It must never include the Master PIN, the locally
derived approval code, or instructions for a temporary fallback.

The request and reply are each exactly four digits; the shared Master PIN is
four digits other than `0000`. The rule is
`floor((request * PIN) / 100) mod 10000`; in conversational copy, multiply,
drop the product's last two digits, then return the last four digits left with
leading zeroes. For example, request `4321` with PIN `6789` produces approval
`3352`.

This is intentional friction, not secure approval. Do not call it encrypted,
unbreakable, brute-force resistant, or suitable for parental control. During
Internal testing, debug and signed release builds also accept `(request + 5656)
mod 10000` as a testing fallback. User-facing copy must not reveal this fallback
or label behavior as a test, override, debug behavior, or Internal-testing
behavior. One build flag selects whether the extra reply is accepted; the SMS
continues to describe only the PIN calculation.

## Implementation Note

Existing code and preference keys may continue to use `accountability` internally to avoid unnecessary data migration. All user-facing labels should use `Keyholder`.
