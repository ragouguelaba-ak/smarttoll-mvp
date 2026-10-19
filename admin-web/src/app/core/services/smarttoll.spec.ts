import { TestBed } from '@angular/core/testing';
import { Smarttoll } from './smarttoll';

describe('Smarttoll', () => {
  let service: Smarttoll;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(Smarttoll);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
