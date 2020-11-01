package generator;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
class Link {

	private final Long id;

	private final String href, description;

}
