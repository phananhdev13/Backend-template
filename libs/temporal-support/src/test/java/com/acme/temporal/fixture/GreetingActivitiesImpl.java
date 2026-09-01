package com.acme.temporal.fixture;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.InboundAdapter;

@InboundAdapter(AdapterKind.WORKFLOW)
public class GreetingActivitiesImpl implements GreetingActivities {

    @Override
    public String composeGreeting(String name) {
        return "Hello, " + name;
    }
}
