package tech.neural7.trace2local.plugin.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixture de controller para o scanner (não é código de produção). */
@RestController
@RequestMapping("/pix")
public class SampleController {

    @PostMapping("/create")
    public String create() {
        return "ok";
    }

    @GetMapping("/{id}")
    public String find() {
        return "ok";
    }
}
