# AirLock Goose Product Language

This document defines the characters, roles, and vocabulary used throughout the app. New UI copy, notifications, SMS text, screenshots, and documentation should follow it.

## Core Concept

AirLock Goose turns a screen-time promise into a playful interaction. The Goose is both the character enforcing the promise and the user's on-screen stand-in when a limited app is blocked. That is intentional: the Goose guards the limit, asks for more time, and is let loose when the request is approved.

The person receiving the request is not the Goose. That person is the Keyholder.

## Roles

| Name | Role | Can do |
| --- | --- | --- |
| AirLock Goose | The product name | Measure selected-app usage, store limits, coordinate blocking and approval |
| the Goose | Mascot, blocker, and stand-in for the user | Guard apps, count time, ask for extra time, be let loose |
| the Keyholder | Trusted person outside the app | Receive a request text, decide whether to approve it, return an approval code |
| the user | Person using the limited phone | Configure AirLock with the Keyholder, use apps, and enter returned approval codes |

The Keyholder may also know or set the master PIN, but the product must not imply that every Keyholder necessarily does. The master PIN and approval-code flow are related protections with separate jobs.

## Emergency Day Pass

Emergency codes are pre-authorized recovery access for cases where the user genuinely needs the phone and the Keyholder is unavailable. They are separate from ordinary extra-time approval codes and from the master PIN.

- The master PIN is required to generate or replace the set.
- A set contains five random 8-digit codes.
- Replacing the set revokes every unused code from the previous set.
- Each code works once and pauses all Goose blocking for 24 hours.
- Goose duty resumes automatically after the pause.
- The UI must call these `emergency codes` or an `emergency day pass`, never a user-created override PIN.
- Do not imply that the Keyholder is making a live approval decision when one is used; approval happened when the codes were generated and entrusted to the user.

## Required Vocabulary

- Product name: `AirLock Goose`
- Monitoring mode: `Goose duty`
- Trusted approval person: `Keyholder`
- Request action: `Text the Keyholder`
- Stored number: `Keyholder phone number`
- Successful unlock action: `Loose the Goose`
- Successful state: `The goose is loose`

Use `AirLock` only when describing the underlying app or system behavior in technical or permission copy. Use `the goose` when the UI is speaking through the mascot.

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
- `AirLock approved your request` because AirLock validates the returned code but does not make the human decision.
- Claims such as `cannot be bypassed` or `parental-control secure`; the current overlay is intentional friction, not device-owner enforcement.

## Approval Flow

The blocking screen should tell one coherent story:

1. The goose has reached the daily limit.
2. The goose chooses how many extra minutes to request.
3. AirLock opens a text to the Keyholder with the request code.
4. The Keyholder decides whether to approve the request and sends back an approval code.
5. The user enters that code in AirLock.
6. The goose is let loose for exactly the approved request duration.

Recommended overlay labels:

1. `Ask for minutes!`
2. `Text the Keyholder!`
3. `Enter approval code!`
4. `Let the goose loose!`

Recommended SMS structure:

`The Goose is asking for 10 minutes of extra time in YouTube! Request code: 123456. If approved, send back the approval code for this Goose request.`

The SMS may identify the requested app, duration, and request code. It must never include the locally derived approval code.

## Implementation Note

Existing code and preference keys may continue to use `accountability` internally to avoid unnecessary data migration. All user-facing labels should use `Keyholder`.
