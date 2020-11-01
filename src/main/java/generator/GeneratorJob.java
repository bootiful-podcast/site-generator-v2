package generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joshlong.git.GitTemplate;
import com.joshlong.templates.MarkdownService;
import com.joshlong.templates.MustacheService;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
@Component
public class GeneratorJob {

	private final JdbcTemplate template;

	private final PodcastRowMapper podcastRowMapper;

	private final SiteGeneratorProperties properties;

	private final MustacheService mustacheService;

	private final GitTemplate gitTemplate;

	private final ObjectMapper objectMapper;

	private final Resource defaultEpisodePhoto = new ClassPathResource(
			"/static/assets/images/a-bootiful-podcast-default-square.jpg");

	private final Environment environment;

	private final Resource staticAssets;

	private final Map<String, String> mapOfRenderedMarkdown = new ConcurrentHashMap<>();

	private final MarkdownService markdownService;

	private final Comparator<PodcastRecord> reversed = Comparator
			.comparing((Function<PodcastRecord, Date>) podcastRecord -> podcastRecord.getPodcast().getDate())
			.reversed();

	GeneratorJob(JdbcTemplate template, MarkdownService markdownService, Environment env, ObjectMapper om,
			PodcastRowMapper podcastRowMapper, SiteGeneratorProperties properties, MustacheService mustacheService,
			GitTemplate gitTemplate, @Value("classpath:/static") Resource staticAssets) {
		this.template = template;
		this.markdownService = markdownService;
		this.objectMapper = om;
		this.environment = env;
		this.staticAssets = staticAssets;
		this.podcastRowMapper = podcastRowMapper;
		this.properties = properties;
		this.mustacheService = mustacheService;
		this.gitTemplate = gitTemplate;
		this.restTemplate = new RestTemplateBuilder()
				.basicAuthentication(this.properties.getApi().getUsername(), this.properties.getApi().getPassword())
				.build();

	}

	private final AtomicReference<String> token = new AtomicReference<>();

	private final RestTemplate restTemplate;

	@SneakyThrows
	private String getToken() {

		if (this.token.get() == null) {
			var tokenUrl = this.properties.getApi().getUri() + "/token";
			var responseEntity = restTemplate.postForEntity(tokenUrl, null, String.class);
			Assert.state(responseEntity.getStatusCode().is2xxSuccessful(),
					() -> String.format(
							"the call to the API server (%s) with the username %s and password %s has failed",
							this.properties.getApi().getUri().toString(), this.properties.getApi().getUsername(),
							this.properties.getApi().getPassword()));
			this.token.set(responseEntity.getBody());
		}

		return this.token.get();
	}

	@SneakyThrows
	private void downloadImageFor(PodcastRecord podcast) {
		var uid = podcast.getPodcast().getUid();
		var imagesDirectory = new File(this.properties.getOutput().getPages(), "episode-photos");
		var file = new File(imagesDirectory, uid + ".jpg");
		try {
			Assert.isTrue(imagesDirectory.mkdirs() || imagesDirectory.exists(), "the imagesDirectory ('"
					+ imagesDirectory.getAbsolutePath() + "') does not exist and could not be created");
			var profilePhotoUrl = new URI(this.properties.getApi().getUri() + "/podcasts/" + uid + "/profile-photo");
			log.info("downloading the image from " + profilePhotoUrl);

			ResponseEntity<Resource> responseEntity = this.restTemplate.getForEntity(profilePhotoUrl, Resource.class);

			try (var img = responseEntity.getBody().getInputStream()) {
				this.copyInputStreamToImage(img, file);
			}
		}
		catch (Exception e) {
			// we can't get a photo for this podcast, so we need to provide a default one.
			log.warn(NestedExceptionUtils
					.buildMessage("couldn't find a podcast with the UID " + podcast.getPodcast().getUid() + ".", e));
			this.copyInputStreamToImage(this.defaultEpisodePhoto.getInputStream(), file);
		}
	}

	@SneakyThrows
	private void copyInputStreamToImage(InputStream in, File file) {
		if (!file.exists()) {
			try (var fin = in; var fout = new FileOutputStream(file)) {
				FileCopyUtils.copy(fin, fout);
				log.info("the image file lives in " + file.getAbsolutePath());
			}
		}
		else {
			log.info("the image file " + file.getAbsolutePath() + " already exists. No need to download it again.");
		}
	}

	private void reset(File file) {
		FileUtils.delete(file);
		FileUtils.ensureDirectoryExists(file);
	}

	@SneakyThrows
	public void build() {
		try {
			if (this.properties.isDisabled()) {
				log.info(this.getClass().getName() + " is not enabled. Skipping...");
				return;
			}
			var dateFormat = DateUtils.date();
			log.info("starting the site generation @ " + dateFormat.format(new Date()));
			var gitClone = this.properties.getOutput().getGitClone();

			var dotGitFilesInGitCloneDirectory = gitClone.listFiles(pathname -> !pathname.getName().equals(".git"));
			if (dotGitFilesInGitCloneDirectory != null) {
				Stream.of(dotGitFilesInGitCloneDirectory).forEach(FileUtils::delete);
			}
			Stream.of(this.properties.getOutput().getItems(), properties.getOutput().getPages()).forEach(this::reset);
			var podcastList = this.template.query(this.properties.getSql().getLoadPodcasts(), this.podcastRowMapper);
			var maxYear = podcastList.stream()//
					.max(Comparator.comparing(Podcast::getDate))//
					.map(podcast -> DateUtils.getYearFor(podcast.getDate()))//
					.orElse(DateUtils.getYearFor(new Date()));
			var allPodcasts = podcastList.stream()
					.peek(pr -> this.mapOfRenderedMarkdown.put(pr.getUid(),
							markdownService.convertMarkdownTemplateToHtml(pr.getDescription()).trim()))
					.map(p -> new PodcastRecord(p, "episode-photos/" + p.getUid() + ".jpg",
							dateFormat.format(p.getDate()), this.mapOfRenderedMarkdown.get(p.getUid())))
					.collect(Collectors.toList());
			var json = buildJsonForAllPodcasts(allPodcasts);
			var jsonFile = new File(this.properties.getOutput().getPages(), "podcasts.json");
			FileCopyUtils.copy(json, new FileWriter(jsonFile));
			Assert.isTrue(jsonFile.exists(), "the json file '" + jsonFile.getAbsolutePath() + "' could not be created");
			allPodcasts.parallelStream().forEach(this::downloadImageFor);
			allPodcasts.sort(this.reversed);
			var top3 = new ArrayList<PodcastRecord>();
			for (var i = 0; i < 3 && i < allPodcasts.size(); i++) {
				top3.add(allPodcasts.get(i));
			}
			var map = this.getPodcastsByYear(allPodcasts);
			var years = new ArrayList<YearRollup>();
			map.forEach((year, podcasts) -> {
				podcasts.sort(this.reversed);
				years.add(new YearRollup(year, podcasts, year.equals(maxYear) ? "active" : ""));
			});
			years.sort(Comparator.comparing(YearRollup::getYear).reversed());
			var pageChromeTemplate = this.properties.getTemplates().getPageChromeTemplate();
			var context = new HashMap<String, Object>();
			context.put("top3", top3);
			context.put("siteGenerationDate", DateUtils.dateAndTime().format(new Date()));
			context.put("years", years);
			context.put("currentYear", DateUtils.getYearFor(new Date()));
			var html = this.mustacheService.convertMustacheTemplateToHtml(pageChromeTemplate, context);
			var page = new File(this.properties.getOutput().getPages(), "index.html");
			FileCopyUtils.copy(html, new FileWriter(page));
			log.info("wrote the template to " + page.getAbsolutePath());
			copyPagesIntoPlace();
			commit();
		}
		finally {
			this.mapOfRenderedMarkdown.clear();
		}
	}

	private void commit() {
		Stream//
				.of(this.environment.getActiveProfiles())//
				.filter(p -> p.equalsIgnoreCase("cloud"))//
				.peek(profile -> log.info("running with " + profile + " active. Going to commit everything to Github"))
				.forEach(x -> this.gitTemplate.executeAndPush(
						git -> Stream.of(Objects.requireNonNull(properties.getOutput().getGitClone().listFiles()))
								.forEach(file -> add(git, file))));
	}

	@SneakyThrows
	private void copyPagesIntoPlace() {
		var toCopy = new ArrayList<Map<File, File>>();
		toCopy.add(Collections.singletonMap(this.staticAssets.getFile(), this.properties.getOutput().getPages()));
		toCopy.add(
				Collections.singletonMap(this.properties.getOutput().getPages(), properties.getOutput().getGitClone()));
		toCopy.forEach(it -> it.forEach((from, to) -> Stream.of(Objects.requireNonNull(from.listFiles()))
				.forEach(f -> FileUtils.copy(f, new File(to, f.getName())))));
	}

	private JsonNode jsonNodeForPodcast(PodcastRecord pr) {
		var objectNode = this.objectMapper.createObjectNode();
		objectNode.put("id", Long.toString(pr.getPodcast().getId()));
		objectNode.put("uid", pr.getPodcast().getUid());
		objectNode.put("title", pr.getPodcast().getTitle());
		objectNode.put("date", pr.getPodcast().getDate().getTime());
		objectNode.put("episodePhotoUri", pr.getPodcast().getPodbeanPhotoUri());
		objectNode.put("description", this.mapOfRenderedMarkdown.get(pr.getPodcast().getUid()));
		objectNode.put("dateAndTime", pr.getDateAndTime()); // correct
		objectNode.put("dataAndTime", pr.getDateAndTime()); // does anything else use this
		// mistaken property?
		objectNode.put("episodeUri",
				this.properties.getApi().getUri() + "/podcasts/" + pr.getPodcast().getUid() + "/produced-audio");
		return objectNode;
	}

	private String printJsonString(JsonNode jsonNode) {
		try {
			var json = this.objectMapper.readValue(jsonNode.toString(), Object.class);
			var objectWriter = this.objectMapper.writerWithDefaultPrettyPrinter();
			return objectWriter.writeValueAsString(json);
		}
		catch (Exception e) {
			ReflectionUtils.rethrowRuntimeException(e);
		}
		return null;
	}

	private String buildJsonForAllPodcasts(List<PodcastRecord> allPodcasts) {
		var collect = allPodcasts.stream().map(this::jsonNodeForPodcast).collect(Collectors.toList());
		var arrayNode = this.objectMapper.createArrayNode().addAll(collect);
		return printJsonString(arrayNode);
	}

	@SneakyThrows
	private void add(Git git, File f) {
		git.add().addFilepattern(f.getName()).call();
		git.commit().setMessage("adding " + f.getName() + " @ " + Instant.now().toString()).call();
		log.info("added " + f.getAbsolutePath());
	}

	private Map<Integer, List<PodcastRecord>> getPodcastsByYear(List<PodcastRecord> podcasts) {
		var map = new HashMap<Integer, List<PodcastRecord>>();
		for (var podcast : podcasts) {
			var calendar = DateUtils.getCalendarFor(podcast.getPodcast().getDate());
			var year = calendar.get(Calendar.YEAR);
			if (!map.containsKey(year)) {
				map.put(year, new ArrayList<>());
			}
			map.get(year).add(podcast);
		}
		map.forEach((key, value) -> value.sort(this.reversed.reversed()));
		return map;
	}

}
