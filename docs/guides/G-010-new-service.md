# G-010 — Adding a service

Before anything else, be sure you need one. A new service buys independent deployment and data
ownership, and costs a network boundary, an eventual-consistency problem, a pipeline and an on-call
surface. A new **feature slice** inside an existing service costs a package and gives you the same
layering. Prefer the slice; split when there is a reason a package cannot give you — a different
release cadence, a different team owning the data, or a genuinely different scaling profile.

## Steps

1. **Create the module.** `services/<name>/pom.xml`, parent `com.acme:backend-template`, and add the
   module to the root `pom.xml`. Copy the dependency block from `services/order-service` and remove
   what the service does not need.

2. **Create the package root** `com.acme.<name>` with `<Name>ServiceApplication`. Copy the
   `@ComponentScan` include filter from `OrderServiceApplication` — it is what makes `@UseCase` and
   the other framework-free role annotations into beans, and without it nothing wires.

3. **Create the first feature slice** as `com.acme.<name>.<feature>` with `domain`, `application`,
   `adapter` and `config` beneath it. See [reference/layout.md](../reference/layout.md).

4. **Add the architecture test.** Copy `ArchitectureTest` verbatim and change the analysed package.
   It is the same file in every service on purpose: no service gets to weaken a rule for itself.

5. **Add the migration directory** `src/main/resources/db/migration` and `V1__…sql`. Set
   `spring.jpa.hibernate.ddl-auto: validate` so a drift between entities and migrations fails at
   startup rather than at the first query.

6. **Add `application.yml`.** Copy the actuator, correlation and messaging blocks from
   `order-service`; they are the platform conventions, not per-service choices.

7. **Register published events.** Any `@EventContract` needs a schema under `contracts/events/`, and
   `acme.messaging.base-packages` must include the service's root package or nothing will provision
   its topics.

8. **Run the gate.** `mvn -pl services/<name> -am verify`. Expect the architecture test to fail
   first with a list of classes that declare no role; that is the intended first experience.

## Naming

Module directory, artifact id and package all use the same name: `order-service`, `order-service`,
`com.acme.order`. The service name is singular and describes the capability, not the technology —
`billing`, not `billing-api`.

## Before you open the pull request

- [ ] `mvn verify` green from the repository root, not just the new module
- [ ] the architecture test analyses the new package and passes
- [ ] a use case specification exists under `docs/use-cases/` for every `@UseCase(id = …)`
- [ ] the service appears in the root `pom.xml` and in `AGENTS.md`'s layout section if it is worth
      calling out
