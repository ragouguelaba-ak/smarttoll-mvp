import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Lanes } from './lanes';

describe('Lanes', () => {
  let component: Lanes;
  let fixture: ComponentFixture<Lanes>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Lanes]
    })
      .compileComponents();

    fixture = TestBed.createComponent(Lanes);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
