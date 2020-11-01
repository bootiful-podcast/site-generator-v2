package generator;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.io.File;
import java.net.URI;

@Data
@ConfigurationProperties(SiteGeneratorProperties.PODCAST_GENERATOR_PROPERTIES)
public class SiteGeneratorProperties {

	public static final String PODCAST_GENERATOR_PROPERTIES = "podcast.generator";

	private boolean disabled;

	private final Sql sql = new Sql();

	private final Templates templates = new Templates();

	private final Output output = new Output();

	private final Launcher launcher = new Launcher();

	private final Api api = new Api();

	@Data
	public static class Api {

		private URI uri;

		private String username;

		private String password;

	}

	@Data
	public static class Output {

		private File items, pages, gitClone;

	}

	@Data
	public static class Templates {

		private Resource episodeTemplate, pageChromeTemplate, yearTemplate;

	}

	@Data
	public static class Sql {

		private String loadPodcasts;

		private String loadLinks;

		private String loadMedia;

	}

	@Data
	public static class Launcher {

		private String requestsQueue = "site-generator-requests-queue";

		private String requestsExchange = this.requestsQueue;

		private String requestsRoutingKey = this.requestsQueue;

	}

}
