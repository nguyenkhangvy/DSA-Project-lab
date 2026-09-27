package vn.edu.hcmiu.sla.school.pages;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import vn.edu.hcmiu.sla.core.Text;

/** The device name on the Devices page, with the same rules and messages as the Python site. */
public class DeviceForm {

    @NotBlank(message = "This field is required.")
    @Size(max = 100, message = "Field cannot be longer than 100 characters.")
    private String name = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : Text.strip(name);
    }
}
