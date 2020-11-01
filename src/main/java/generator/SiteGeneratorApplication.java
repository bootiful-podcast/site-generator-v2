package generator;

import fm.bootifulpodcast.rabbitmq.RabbitMqHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;

import java.io.File;

/**
 * The job runs both when the application starts up <em>and</em> when requests come via
 * the RabbitMQ queue defined in
 * {@link SiteGeneratorProperties.Launcher#getRequestsQueue()}.
 */
@Log4j2
@EnableConfigurationProperties(SiteGeneratorProperties.class)
@SpringBootApplication
@RequiredArgsConstructor
public class SiteGeneratorApplication {

	private final GeneratorJob generatorJob;

	@EventListener(ApplicationReadyEvent.class)
	public void ready() {
		this.generatorJob.build();
	}

	@Bean
	@Profile("!ci") // this is activated _only_ if we're _not_ in the `ci` profile!
	@ConditionalOnProperty(name = "online", havingValue = "true", matchIfMissing = true)
	IntegrationFlow launchRequestHandlerIntegrationFlow(ConnectionFactory cf, SiteGeneratorProperties properties,
			RabbitMqHelper rabbitMqHelper) {

		log.info("installing a launch request handler integration flow...");
		log.info("requests-queue: " + properties.getLauncher().getRequestsQueue());

		var amqpInboundAdapter = Amqp //
				.inboundAdapter(cf, properties.getLauncher().getRequestsQueue()) //
				.get();
		return IntegrationFlows //
				.from(amqpInboundAdapter) //
				.handle(String.class, (payload, headers) -> {
					this.generatorJob.build();
					return null;
				})//
				.get();
	}

	public static void main(String[] args) {

		var newArgs = (args.length == 0) ? //
				new String[] { "runtime=" + System.currentTimeMillis() } : //
				args;
		SpringApplication.run(SiteGeneratorApplication.class, newArgs);
	}

}
