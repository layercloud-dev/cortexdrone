package ir.layercloud.cortexdrone.action.movement.impl;

import ir.layercloud.cortexdrone.action.movement.MovementAction;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** Single-use command: read the breadcrumb trail. */
@RequiredArgsConstructor
final class BreadcrumbsAction extends MovementAction {
    private final MovementRequest request;

    @Override
    public void execute(MovementContext context) {
        DroneController controller = request.getController();
        if (controller == null) {
            context.result(MovementResult.failure("No active drone"));
            return;
        }
        int limit = Math.min(256, Math.max(4, request.getLimit()));
        List<int[]> history = new ArrayList<int[]>(controller.navigationHistory);
        int start = Math.max(0, history.size() - limit);
        List<int[]> page = new ArrayList<int[]>();
        for (int i = start; i < history.size(); i++) {
            page.add(history.get(i));
        }
        context.result(MovementResult.breadcrumbs(page, controller.lastSurfaceAnchor, history.size()));
    }
}
