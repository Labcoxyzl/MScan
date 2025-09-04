/**
 * @program: Tai-e-microservice
 * @author: LFY
 * @create: 2024-03-26 15:56
 **/

package fdu.secsys.microservice.entity;

import lombok.Data;

import java.util.List;

@Data
public class Service {

    // service name
    private String name;

    // service route in gateway
    private List<String> route;

    // class list in the service, use for determine the service name of the specific class
    private List<String> classList;

    public Service(String name, List<String> route, List<String> classList) {
        this.name = name;
        this.route = route;
        this.classList = classList;
    }

}
