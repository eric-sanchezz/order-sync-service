package dev.ericsanchez.ordersync.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture rules, checked on the production classes only (tests may use field injection, sleeps and so on).
 * Each test names the change that breaks it; each of those changes was made by hand to confirm the rule fails.
 */
class ArchitectureTest {

	private static final String BASE = "dev.ericsanchez.ordersync";

	private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
		.importPackages(BASE);

	// The order mapping stays pure: no framework, no database, no HTTP, no knowledge of the queue or the ERP client.
	// Breaks if: MoneyParser gets @Component, or OrderTransformer gets a java.net.URI field.
	@Test
	void transformIsPureLogic() {
		noClasses().that()
			.resideInAPackage(BASE + ".transform..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("org.springframework..", "jakarta.persistence..", BASE + ".erp..", BASE + ".job..",
					"java.net..")
			.check(MAIN_CLASSES);
	}

	// Controllers talk to services, never to the database: no Spring Data repositories or their custom query
	// fragments (SyncJobQueries, OutboxQueries), and no low-level data access either (Spring JDBC such as JdbcClient,
	// JdbcTemplate or NamedParameterJdbcTemplate, Spring ORM, JPA's EntityManager, JDBC or a DataSource).
	// Breaks if: AdminJobController gets a SyncJobRepository, a SyncJobQueries, a JdbcClient, an EntityManager or a
	// DataSource field (each tried on its own).
	@Test
	void controllersDoNotAccessTheDatabase() {
		noClasses().that()
			.resideInAnyPackage(BASE + ".webhook..", BASE + ".admin..", BASE + ".stock..")
			.and()
			.areAnnotatedWith(RestController.class)
			.should()
			.dependOnClassesThat()
			.areAssignableTo(Repository.class)
			.orShould()
			.dependOnClassesThat()
			.haveSimpleNameEndingWith("Repository")
			.orShould()
			.dependOnClassesThat()
			.haveSimpleNameEndingWith("Queries")
			.orShould()
			.dependOnClassesThat()
			.resideInAnyPackage("org.springframework.jdbc..", "org.springframework.orm..", "jakarta.persistence..",
					"java.sql..", "javax.sql..")
			.check(MAIN_CLASSES);
	}

	// The beans of each integration live in its own package (ErpConfig, WebhookConfig, StockConfig) and BackoffPolicy
	// in the neutral package retry, so config depends on no package that depends on config.
	// Breaks if: AppConfig gets an ErpClient field (config -> erp -> observability -> config).
	@Test
	void topLevelPackagesHaveNoCycles() {
		slices().matching(BASE + ".(*)..").should().beFreeOfCycles().check(MAIN_CLASSES);
	}

	// Money is long cents in the packages that carry it (shop order in, transformation, ERP order out). Checked:
	// Double, Float and BigDecimal anywhere in the dependencies (fields, signatures, generics, calls), and the
	// primitives double/float in fields, signatures and in the signature of any method called or field accessed
	// (Math.round(double), Long.doubleValue()). java.text.NumberFormat and its subclasses (DecimalFormat) are banned
	// too: NumberFormat.parse(..).longValue() goes through a Double without any double in a signature.
	// Limits: a local double that never goes through a call or field is invisible in the bytecode's dependencies, and
	// so is floating point hidden inside a library method whose signature has none (String in, long out).
	// Breaks if: a static double field in ShopOrder, a List<BigDecimal> field in ErpOrder, a method returning float
	// in MoneyParser, a call to Math.floor(double) in OrderTransformer, NumberFormat.parse(..).longValue() in
	// MoneyParser, or a DecimalFormat field in MoneyParser (each tried on its own).
	@Test
	void moneyIsNeverFloatingPointOrBigDecimal() {
		noClasses().that()
			.resideInAnyPackage(BASE + ".transform..", BASE + ".erp..", BASE + ".webhook..")
			.should()
			.dependOnClassesThat()
			.belongToAnyOf(Double.class, Float.class, BigDecimal.class)
			.orShould()
			.dependOnClassesThat()
			.areAssignableTo(NumberFormat.class)
			.orShould(useFloatingPointPrimitives())
			.check(MAIN_CLASSES);
	}

	// Constructor injection (or @ConfigurationProperties) only: dependencies are explicit and fields can be final.
	// Covers @Autowired, @Value, jakarta.inject.@Inject and jakarta.annotation.@Resource on fields.
	// Breaks if: an @Autowired field, a @Value("${...}") field or a @Resource field in AdminJobService (each on its
	// own).
	@Test
	void noFieldInjection() {
		noFields().should()
			.beAnnotatedWith(Autowired.class)
			.orShould()
			.beAnnotatedWith(Value.class)
			.orShould()
			.beAnnotatedWith("jakarta.inject.Inject")
			.orShould()
			.beAnnotatedWith(Resource.class)
			.check(MAIN_CLASSES);
	}

	// Waiting is the scheduler's and the database's job (next_attempt_at, leases), never a sleeping thread. Covers
	// every JDK way to put the current thread to sleep: Thread.sleep, TimeUnit.sleep and LockSupport.park*.
	// Breaks if: Thread.sleep(1), Thread.sleep(Duration), TimeUnit.MILLISECONDS.sleep(1) or LockSupport.parkNanos(1)
	// in SyncWorker (each on its own).
	@Test
	void noSleepingInProductionCode() {
		noClasses().should()
			.callMethodWhere(DescribedPredicate.describe("Thread.sleep, TimeUnit.sleep or LockSupport.park*",
					(JavaMethodCall call) -> isSleep(call)))
			.check(MAIN_CLASSES);
	}

	private static boolean isSleep(JavaMethodCall call) {
		JavaClass owner = call.getTargetOwner();
		String name = call.getName();
		return (owner.isEquivalentTo(Thread.class) && name.equals("sleep"))
				|| (owner.isAssignableTo(TimeUnit.class) && name.equals("sleep"))
				|| (owner.isEquivalentTo(LockSupport.class) && name.startsWith("park"));
	}

	private static ArchCondition<JavaClass> useFloatingPointPrimitives() {
		return new ArchCondition<>("use the primitives double or float") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				Stream<String> members = Stream.of(
						javaClass.getFields()
							.stream()
							.filter(field -> isFloatingPoint(field.getRawType()))
							.map(field -> "field " + field.getFullName()),
						javaClass.getCodeUnits()
							.stream()
							.filter(code -> isFloatingPoint(code.getRawReturnType())
									|| code.getRawParameterTypes().stream().anyMatch(ArchitectureTest::isFloatingPoint))
							.map(code -> "signature of " + code.getFullName()),
						javaClass.getAccessesFromSelf()
							.stream()
							.filter(access -> touchesFloatingPoint(access.getTarget()))
							.map(JavaAccess::getDescription))
					.flatMap(stream -> stream);
				members.forEach(description -> events.add(SimpleConditionEvent.satisfied(javaClass, description)));
			}
		};
	}

	private static boolean touchesFloatingPoint(AccessTarget target) {
		if (target instanceof AccessTarget.FieldAccessTarget field) {
			return isFloatingPoint(field.getRawType());
		}
		if (target instanceof AccessTarget.CodeUnitAccessTarget codeUnit) {
			List<JavaClass> parameters = codeUnit.getRawParameterTypes();
			return isFloatingPoint(codeUnit.getRawReturnType())
					|| parameters.stream().anyMatch(ArchitectureTest::isFloatingPoint);
		}
		return false;
	}

	/** double, float and arrays of them. */
	private static boolean isFloatingPoint(JavaClass type) {
		JavaClass element = type.getBaseComponentType();
		return element.isEquivalentTo(double.class) || element.isEquivalentTo(float.class);
	}

}
