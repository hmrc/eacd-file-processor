# Pull Request for {ticket number}

## Description
<!-- Provide a brief summary of the changes in this PR and link to the relevant ticket(s) -->

## Author Checklist

- [ ] Acceptance criteria for the ticket have been met
- [ ] Relevant unit and integration tests have been added. Code coverage has not dropped below the minimum threshold — aim for 100% where possible
- [ ] If changes have been made to a microservice, the `run_all_tests` shell script has been executed
- [ ] Dependencies have been reviewed — straightforward updates have been applied and tickets have been raised for any outstanding complex dependency updates
- [ ] Any newly introduced warnings have been fixed, and pre-existing warnings carried over have been addressed where doing so does not introduce scope creep — implemented in a separate commit
- [ ] PR BOT comments have been addressed where scope allows; a new ticket has been raised for anything that falls outside the current scope
- [ ] Screenshots of any new functionality have been attached to the relevant ticket(s)
- [ ] Acceptance tests have been run locally and changes have been manually tested before raising this PR
- [ ] Where applicable, smoke tests have been run and manual checks have been performed in QA

---

## Reviewer Checklist

- [ ] The PR builder has passed
- [ ] Acceptance criteria for the ticket have been met
- [ ] There are no merge conflicts on the PR
- [ ] The `run_all_tests` shell script has been executed (runs test suites, checks coverage, and checks for dependency updates)
- [ ] Test coverage has not dropped below the value on the main branch
- [ ] Tests have been added to cover any new logic, including edge cases
- [ ] Changes have been manually tested
- [ ] Performance tests have been run and results recorded for any ticket of 5 points or more (where available and covering changed functionality)
- [ ] Code has been written following best practices

