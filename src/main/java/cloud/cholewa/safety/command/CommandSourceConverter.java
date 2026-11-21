package cloud.cholewa.safety.command;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

//?source= by its word (dashboard, manual); any other word is refused with a 400 - Boot adds a Converter bean to the
//conversion of WebFlux
@Component
public class CommandSourceConverter implements Converter<String, CommandSource> {

    @Override
    public CommandSource convert(final String source) {
        return CommandSource.fromValue(source)
            .orElseThrow(() -> new IllegalArgumentException("The source is dashboard or manual, not " + source));
    }
}
