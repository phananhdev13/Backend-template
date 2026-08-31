---
name: new-service
description: Scaffold a new deployable service module in this monorepo - pom, application class with the component-scan filter, first feature slice, architecture test, migrations and configuration. Use when asked to add a new service or microservice, or to decide whether a capability needs its own service rather than a feature slice in an existing one.
---

# Adding a service

## First, be sure you need one

A new service buys independent deployment, independent scaling, and clear data ownership. It costs a
network boundary, an eventual-consistency problem, a pipeline, a dashboard and an on-call surface.

A new **feature slice** inside an existing service gives you the same layering, the same enforced
boundaries and the same testability for the price of a package. Prefer it. Split a service out when
there is a reason a package cannot give you:

- a genuinely different release cadence
- a different team owning the data
- a scaling profile that would otherwise force the whole service to scale with it
- a regulatory boundary

"It feels like a different thing" is a reason for a slice, not a service.

## Steps

1. **Module.** `services/<name>/pom.xml`, parent `com.acme:backend-template`; add it to the root
   `<modules>`. Copy the dependency block from `services/order-service` and delete what you do not
   need. Do not add versions — the root manages them.

2. **Application class.** `com.acme.<name>.<Name>ServiceApplication`. Copy the `@ComponentScan`
   include filter from `OrderServiceApplication` verbatim:

   ```java
   @ComponentScan(includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
       classes = {UseCase.class, ReadModel.class, OutboundAdapter.class, InboundAdapter.class, Command.class}))
   ```

   Without it nothing wires. The role annotations live in `libs/kernel`, which has no Spring
   dependency, so they are not stereotypes and Spring will not find them. That is the deliberate
   cost of keeping the application layer framework-free.

   Add the `Clock` bean while you are there — the domain takes time as a parameter.

3. **First feature slice.** `com.acme.<name>.<feature>` with `domain`, `application`, `adapter`,
   `config`. See [docs/reference/layout.md](../../../docs/reference/layout.md).

4. **Architecture test.** Copy `ArchitectureTest` and change the analysed package. It is identical
   in every service on purpose: no service weakens a rule for itself.

5. **Migrations.** `src/main/resources/db/migration/V1__…sql`, with
   `spring.jpa.hibernate.ddl-auto: validate` so entity/schema drift fails at startup rather than at
   the first query that hits the missing column.

6. **Configuration.** Copy the actuator, correlation and messaging blocks from `order-service`;
   those are platform conventions, not per-service choices. Set
   `acme.messaging.base-packages` to the service's root package, or its topics will not be
   provisioned.

7. **Verify.** `mvn -pl services/<name> -am verify`.

Expect the architecture test to fail first, listing classes that declare no role. That is the
intended first experience of this repo.

## Naming

Directory, artifact id and package share one name: `order-service`, `order-service`,
`com.acme.order`. Name the capability, not the technology — `billing`, not `billing-api`.

## Registering published events

Any `@EventContract` needs a schema checked in under `contracts/events/`, or
`EventContractRules.everyContractHasASchemaFile` fails. That file is what consumers in other
repositories generate from.
