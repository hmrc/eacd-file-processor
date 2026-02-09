
# eacd-file-processor

This service will be used as part of bulk de-enrolment processing

A helpdesk user will upload a document and this service will retrieve and store this document


# Running tests
sbt clean coverage test it/test coverageReport

# Running service
sbt "run 9000"

### Running the test suite
```
sbt clean coverage test coverageReport
```

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").