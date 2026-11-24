package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderAction;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.service.OrderWorkflow;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One step of the order workflow as {@code GET /orders/workflow} lists it, built from
 * {@link OrderWorkflow.Transition}.
 * <p>
 * {@code actors} are the roles that may take the step ({@code MANUAL}) or whose work causes it
 * ({@code COMPUTED}), by enum name and sorted like {@code RoleEnum}. {@code automatic} says that a scheduled routine
 * takes the step as well. {@code condition} is what else has to hold, in words, and absent when nothing does - the
 * workflow only names it, the services check it against the data. {@code label} is the few words the step carries
 * in a drawing, absent when it needs none. A {@code shortcut} is allowed like any other step, but a drawing
 * leaves it out (see {@code OrderWorkflow.Transition}).
 */
public record OrderTransitionDto(
        OrderStatus from,
        OrderStatus to,
        OrderAction action,
        OrderWorkflow.Trigger trigger,
        List<RoleEnum> actors,
        boolean automatic,
        @Nullable String condition,
        @Nullable String label,
        boolean shortcut
) {
    public static OrderTransitionDto of(OrderWorkflow.Transition transition) {
        return new OrderTransitionDto(transition.from(), transition.to(), transition.action(), transition.trigger(),
                transition.actors().stream().sorted().toList(), transition.automatic(),
                transition.condition().isBlank() ? null : transition.condition(),
                transition.label().isBlank() ? null : transition.label(), transition.shortcut());
    }
}
