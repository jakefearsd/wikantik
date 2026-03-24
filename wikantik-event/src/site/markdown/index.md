# Wikantik Event Subsystem

Wiki event model and listener infrastructure. Components communicate through
typed `WikiEvent` objects rather than direct coupling, enabling loose
decoupling between the rendering pipeline, storage providers, and UI layer.
