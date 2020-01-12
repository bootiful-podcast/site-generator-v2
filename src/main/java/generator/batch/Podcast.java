package generator.batch;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.Date;

@Data
@RequiredArgsConstructor
class Podcast {

	private final Long id;

	private final Date date;

	private final String description, podbeanMediaUri, notes, title, transcript, uid,
			s3OutputFileName, s3FullyQualifiedUri;

	private final Collection<Media> media;

	private final Collection<Link> links;

}