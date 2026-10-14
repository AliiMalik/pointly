"""Request and response models (SPEC 6.2, 6.3, 6.7)."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

MAX_ELEMENTS = 120

Action = Literal["tap", "type", "scroll", "drag", "back", "home", "ask", "done", "cant_help"]
ScrollDirection = Literal["up", "down", "left", "right"]
ELEMENT_ACTIONS = {"tap", "type", "scroll", "drag"}
DIRECTION_ACTIONS = {"scroll", "drag"}


class Element(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: int
    text: str | None = None
    desc: str | None = None
    hint: str | None = None
    label: str | None = None
    view_id: str | None = None
    cls: str = "View"
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False
    checked: bool | None = None
    selected: bool | None = None
    bounds: list[int] = Field(min_length=4, max_length=4)
    range: list[float] | None = Field(default=None, min_length=3, max_length=3)  # sliders: [min, max, current]


class Screen(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    package: str
    window_title: str | None = None
    width: int = Field(gt=0)
    height: int = Field(gt=0)
    elements: list[Element]

    @model_validator(mode="after")
    def _ids_unique(self) -> "Screen":
        ids = [e.id for e in self.elements]
        if len(ids) != len(set(ids)):
            raise ValueError("element ids must be unique")
        return self


class HistoryItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    step: int
    instruction_roman: str = ""
    target_label: str | None = None
    outcome: Literal["done", "deviated", "skipped"] = "done"


class Prefs(BaseModel):
    model_config = ConfigDict(extra="ignore")

    caption: Literal["roman", "urdu"] = "roman"


class PlanRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    session_id: str = Field(min_length=1, max_length=100)
    goal_text: str = Field(min_length=1, max_length=1000)
    step_index: int = Field(ge=1)
    history: list[HistoryItem] = []
    screen: Screen
    prefs: Prefs = Prefs()

    @field_validator("screen")
    @classmethod
    def _not_too_many(cls, screen: Screen) -> Screen:
        # SPEC 6.4 step 1: reject screens with more than 120 elements (422).
        if len(screen.elements) > MAX_ELEMENTS:
            raise ValueError(f"screen has {len(screen.elements)} elements; max is {MAX_ELEMENTS}")
        return screen


class LLMPlan(BaseModel):
    """What the model must return (structured output schema)."""

    action: Action
    element_id: int | None = None
    scroll_direction: ScrollDirection | None = None
    say_urdu: str
    say_roman: str
    goal_key: str
    confidence: float
    reason: str


class PlanResponse(LLMPlan):
    latency_ms: int


class SttResponse(BaseModel):
    text_urdu: str
    text_roman: str
    latency_ms: int


class TtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=500)
