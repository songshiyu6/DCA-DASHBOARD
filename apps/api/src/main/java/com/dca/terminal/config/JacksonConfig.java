package com.dca.terminal.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule decimalAsStringModule() {
        SimpleModule module = new SimpleModule("decimal-as-string");
        module.addSerializer(BigDecimal.class, new ToStringSerializer() {
            @Override
            public void serialize(Object value, JsonGenerator generator,
                                  com.fasterxml.jackson.databind.SerializerProvider provider)
                    throws java.io.IOException {
                generator.writeString(((BigDecimal) value).toPlainString());
            }
        });
        return module;
    }
}
