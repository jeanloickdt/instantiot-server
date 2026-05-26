# Contributing to InstantIoT Server

Thanks for taking the time to look at this project. Contributions are
welcome, whether you are reporting a bug, suggesting an improvement, or
sending a pull request.

This guide explains how to do it cleanly.

---

## Before you start

Read [`ARCHITECTURE.md`](ARCHITECTURE.md) — it covers the project layout
and the main flows in about ten minutes. It will help your contribution
fit naturally into the codebase.

---

## Contributor License Agreement (CLA)

**Every contribution to this project is subject to the
[Contributor License Agreement](CLA.md).**

In short, by contributing you certify that the code is yours to submit,
and you grant the maintainer the right to distribute and re-license your
work under any license — including a future commercial license. You
keep the copyright on your own work; the CLA is a license grant, not a
transfer of ownership.

Please read [`CLA.md`](CLA.md) before submitting your first pull
request, and confirm your acceptance by commenting on the pull request:

> I have read the CLA and I accept it.

This needs to be done **once per contributor**. Future pull requests
from the same account are automatically covered.

---

## Workflow

1. **Fork** the repository on GitHub.
2. **Create a branch** from `main` with a short, descriptive name:
   - `fix/<short-description>` for bug fixes
   - `feature/<short-description>` for new functionality
   - `chore/<short-description>` for refactors, cleanups, or tooling
3. **Make your changes** locally. Keep them focused — one logical change
   per pull request makes review much easier.
4. **Make sure the build passes** before pushing:
   ```bash
   ./gradlew build
   ```
5. **Push** your branch to your fork and **open a pull request** against
   the `main` branch of this repository.
6. **Accept the CLA** by commenting on your pull request (see above),
   if you have not done so before.

---

## What we expect from a pull request

- **Clear commit messages.** The first line is a short imperative
  summary (under ~70 characters), followed by a blank line and an
  optional longer body explaining the *why*, not just the *what*.
- **Minimal diffs.** Touch only the lines that need to change. Do not
  reformat unrelated files in the same pull request — that drowns the
  actual change in noise.
- **Respect the existing code style and comments.** The codebase
  follows consistent conventions; please follow them too. Comments are
  written in English throughout the project.
- **Tests where it makes sense.** If you change behaviour that can be
  reasonably tested, add or update a test.
- **No unrelated changes.** If you spot something else to fix, open a
  separate pull request for it.

If your pull request is large or touches sensitive areas (auth, relay,
database schema, protocol), open an issue first to discuss the
approach. It saves time on both sides.

---

## Review and merging

Pull requests are reviewed by the maintainer, **Djoufack Tsobeng Jean
Loïck**. Expect feedback: questions, suggestions, or requests for
changes. This is the normal review process, not personal criticism — it
helps the codebase stay coherent.

The maintainer decides whether a pull request is merged. Acceptance is
not automatic, even after the CLA is signed and tests pass: the
maintainer may decline a contribution that does not fit the direction
of the project. When that happens, the reasoning will be explained on
the pull request.

---

## Reporting bugs and suggesting features

You do not need to write code to contribute. Useful bug reports and
clear feature proposals are valuable contributions in their own right.

When opening an issue:

- **Bug report**: describe what you expected, what actually happened,
  the steps to reproduce it, and your environment (OS, JDK version,
  server version).
- **Feature proposal**: explain the use case first, then the proposed
  solution. The use case matters more than the implementation — there
  may be a simpler way to solve your problem.

---

## A note on direction

InstantIoT Server is intentionally focused: a self-hosted IoT relay
between maker boards and a mobile app, with a small admin panel.
Contributions that fit this scope are welcomed warmly. Contributions
that pull the project into adjacent territories (full home automation
suite, generic database product, etc.) will probably be declined, even
if they are well written — they belong in a different project.

Thanks again for being here.
