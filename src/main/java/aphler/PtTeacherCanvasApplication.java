package aphler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
public class PtTeacherCanvasApplication  extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(PtTeacherCanvasApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(PtTeacherCanvasApplication.class, args);
    }
}
